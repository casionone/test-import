/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.message.publisher;

import com.webank.wedatasphere.linkis.common.utils.JavaLog;
import com.webank.wedatasphere.linkis.message.builder.DefaultServiceMethodContext;
import com.webank.wedatasphere.linkis.message.builder.MessageJob;
import com.webank.wedatasphere.linkis.message.builder.ServiceMethodContext;
import com.webank.wedatasphere.linkis.message.context.AbstractMessageSchedulerContext;
import com.webank.wedatasphere.linkis.message.exception.MessageWarnException;
import com.webank.wedatasphere.linkis.message.parser.ImplicitMethod;
import com.webank.wedatasphere.linkis.message.parser.ServiceMethod;
import com.webank.wedatasphere.linkis.message.scheduler.MethodExecuteWrapper;
import com.webank.wedatasphere.linkis.message.utils.MessageUtils;
import com.webank.wedatasphere.linkis.protocol.message.RequestProtocol;

import com.webank.wedatasphere.linkis.rpc.MessageErrorConstants;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.webank.wedatasphere.linkis.message.conf.MessageSchedulerConf.CONTEXT_KEY;


public abstract class AbstractMessagePublisher extends JavaLog implements MessagePublisher {

    private AbstractMessageSchedulerContext context;

    public AbstractMessagePublisher(AbstractMessageSchedulerContext context) {
        this.context = context;
    }

    public void setContext(AbstractMessageSchedulerContext context) {
        this.context = context;
    }

    /**
     * key???requestProtocol???????????????Map??????key???groupName
     */
    private final Map<String, Map<String, List<ServiceMethod>>> protocolServiceMethodCache = new ConcurrentHashMap<>();


    @Override
    public MessageJob publish(RequestProtocol requestProtocol) {
        return publish(requestProtocol, new DefaultServiceMethodContext());
    }

    @Override
    public MessageJob publish(RequestProtocol requestProtocol, ServiceMethodContext serviceMethodContext) {
        logger().debug(String.format("receive request:%s", requestProtocol.getClass().getName()));
        serviceMethodContext.putIfAbsent(CONTEXT_KEY, this.context);
        Map<String, List<MethodExecuteWrapper>> methodExecuteWrappers = getMethodExecuteWrappers(requestProtocol);
        MessageJob messageJob = this.context.getJobBuilder().of()
                .with(serviceMethodContext).with(requestProtocol).with(this.context)
                .with(methodExecuteWrappers).build();
        this.context.getScheduler().submit(messageJob);
        return messageJob;
    }

    private Map<String, List<MethodExecuteWrapper>> getMethodExecuteWrappers(RequestProtocol requestProtocol) {
        String protocolName = requestProtocol.getClass().getName();
        Map<String, List<ServiceMethod>> protocolServiceMethods = this.protocolServiceMethodCache.get(protocolName);
        //???????????????????????????
        if (protocolServiceMethods == null) {
            Map<String, List<ServiceMethod>> serviceMethodCache = this.context.getServiceRegistry().getServiceMethodCache();
            Map<String, List<ImplicitMethod>> implicitMethodCache = this.context.getImplicitRegistry().getImplicitMethodCache();
            //?????????????????????????????????????????????????????????
            Map<String, List<ServiceMethod>> serviceMatchs = serviceMethodCache.entrySet().stream()
                    .filter(e -> MessageUtils.isAssignableFrom(e.getKey(), protocolName))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            //??????implicit??????????????????????????????????????????????????????????????????implicit???????????????????????????servicematchKeys ????????????
            Map<String, List<ServiceMethod>> implicitMatchs = new HashMap<>();
            for (Map.Entry<String, List<ImplicitMethod>> implicitEntry : implicitMethodCache.entrySet()) {
                //??????implicitMehtod??????input?????????protocolName ?????????or??????
                String implicitEntryKey = implicitEntry.getKey();
                List<ImplicitMethod> implicitEntryValue = implicitEntry.getValue();
                // ???????????? ????????? ???service???????????????????????????
                Map<String, List<ServiceMethod>> implicitServiceMethods = serviceMethodCache.entrySet().stream()
                        .filter(e -> MessageUtils.isAssignableFrom(e.getKey(), implicitEntryKey))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                //??????implicit????????????protocolName?????????????????????????????????????????????protocol????????????????????????????????????protocol?????????
                if (!MessageUtils.isAssignableFrom(implicitEntryKey, protocolName) && !implicitServiceMethods.isEmpty()) {
                    for (Map.Entry<String, List<ServiceMethod>> implicitServiceMethodEntry : implicitServiceMethods.entrySet()) {
                        String implicitServiceMethodEntryKey = implicitServiceMethodEntry.getKey();
                        List<ServiceMethod> implicitServiceMethodEntryValue = implicitServiceMethodEntry.getValue();
                        //??????????????????implicit???
                        List<ServiceMethod> filteredServiceMethods = implicitServiceMethodEntryValue.stream()
                                .filter(ServiceMethod::isAllowImplicit)
                                .collect(Collectors.toList());
                        //??????????????????????????????????????????protocol??????????????????
                        List<ImplicitMethod> filteredImplicitMethods = implicitEntryValue.stream()
                                .filter(v -> MessageUtils.isAssignableFrom(v.getInput(), protocolName))
                                .collect(Collectors.toList());
                        if (!filteredServiceMethods.isEmpty() && !filteredImplicitMethods.isEmpty()) {
                            //????????????ServiceMethod ??????????????????????????????????????????service??????
                            for (ServiceMethod filteredServiceMethod : filteredServiceMethods) {
                                Object service = filteredServiceMethod.getService();
                                //???service??????
                                Optional<ImplicitMethod> first = filteredImplicitMethods.stream()
                                        .filter(m -> m.getImplicitObject() == service).findFirst();
                                if (first.isPresent()) {
                                    filteredServiceMethod.setImplicitMethod(first.get());
                                } else {
                                    // TODO: 2020/7/30  ???????????????????????????????????????scala??????
                                    //????????????????????????
                                    filteredServiceMethod.setImplicitMethod(filteredImplicitMethods.get(0));
                                }
                            }
                            //??????????????????
                            implicitMatchs.put(implicitServiceMethodEntryKey, filteredServiceMethods);
                        }
                    }
                }
            }
            //merge
            serviceMatchs.putAll(implicitMatchs);
            //group by chain name ???????????????group?????????protocol??????????????????????????????????????????chain???
            serviceMatchs = serviceMatchs.values().stream().flatMap(Collection::stream).collect(Collectors.groupingBy(ServiceMethod::getChainName));
            //order??????
            for (List<ServiceMethod> value : serviceMatchs.values()) {
                Integer repeatOrder = MessageUtils.repeatOrder(value);
                if (repeatOrder != null && !MessageUtils.orderIsLast(repeatOrder, value)) {
                    throw new MessageWarnException(MessageErrorConstants.MESSAGE_ERROR(),
                            String.format("repeat "
                            + "order : %s for request %s", repeatOrder, protocolName));
                }
            }
            this.protocolServiceMethodCache.put(protocolName, serviceMatchs);
        }
        //clone ???????????????
        return serviceMethod2Wrapper(this.protocolServiceMethodCache.get(protocolName));
    }

    private Map<String, List<MethodExecuteWrapper>> serviceMethod2Wrapper(Map<String, List<ServiceMethod>> source) {
        HashMap<String, List<MethodExecuteWrapper>> target = new HashMap<>();
        source.forEach((k, v) -> target.put(k, v.stream().map(MethodExecuteWrapper::new).collect(Collectors.toList())));
        return target;
    }


}
