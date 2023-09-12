/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.queue.discovery;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.QueueId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.TenantProfileId;
import org.thingsboard.server.common.data.id.UUIDBased;
import org.thingsboard.server.common.data.queue.Queue;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.ServiceInfo;
import org.thingsboard.server.queue.discovery.event.PartitionChangeEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class HashPartitionServiceTest {

    public static final int ITERATIONS = 1000000;
    public static final int SERVER_COUNT = 3;
    private HashPartitionService clusterRoutingService;

    private TbServiceInfoProvider discoveryService;
    private TenantRoutingInfoService routingInfoService;
    private ApplicationEventPublisher applicationEventPublisher;
    private QueueRoutingInfoService queueRoutingInfoService;

    private String hashFunctionName = "murmur3_128";

    @Before
    public void setup() throws Exception {
        discoveryService = mock(TbServiceInfoProvider.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        routingInfoService = mock(TenantRoutingInfoService.class);
        queueRoutingInfoService = mock(QueueRoutingInfoService.class);
        clusterRoutingService = createPartitionService();
        ServiceInfo currentServer = ServiceInfo.newBuilder()
                .setServiceId("tb-core-0")
                .addAllServiceTypes(Collections.singletonList(ServiceType.TB_CORE.name()))
                .build();
//        when(queueService.resolve(Mockito.any(), Mockito.anyString())).thenAnswer(i -> i.getArguments()[1]);
//        when(discoveryService.getServiceInfo()).thenReturn(currentServer);
        List<ServiceInfo> otherServers = new ArrayList<>();
        for (int i = 1; i < SERVER_COUNT; i++) {
            otherServers.add(ServiceInfo.newBuilder()
                    .setServiceId("tb-rule-" + i)
                    .addAllServiceTypes(Collections.singletonList(ServiceType.TB_CORE.name()))
                    .build());
        }

        clusterRoutingService.recalculatePartitions(currentServer, otherServers);
    }

    @Test
    public void testDispersionOnMillionDevices() {
        List<DeviceId> devices = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            devices.add(new DeviceId(Uuids.timeBased()));
        }
        testDevicesDispersion(devices);
    }

    private void testDevicesDispersion(List<DeviceId> devices) {
        long start = System.currentTimeMillis();
        Map<Integer, Integer> map = new HashMap<>();
        for (DeviceId deviceId : devices) {
            TopicPartitionInfo address = clusterRoutingService.resolve(ServiceType.TB_CORE, TenantId.SYS_TENANT_ID, deviceId);
            Integer partition = address.getPartition().get();
            map.put(partition, map.getOrDefault(partition, 0) + 1);
        }

        checkDispersion(start, map, ITERATIONS, 5.0);
    }

    @SneakyThrows
    @Test
    public void testDispersionOnResolveByPartitionIdx() {
        int serverCount = 5;
        int tenantCount = 1000;
        int queueCount = 3;
        int partitionCount = 3;

        List<ServiceInfo> services = new ArrayList<>();

        for (int i = 0; i < serverCount; i++) {
            services.add(ServiceInfo.newBuilder().setServiceId("RE-" + i).build());
        }

        long start = System.currentTimeMillis();
        Map<String, Integer> map = new HashMap<>();
        services.forEach(s -> map.put(s.getServiceId(), 0));

        Random random = new Random();
        long ts = new SimpleDateFormat("dd-MM-yyyy").parse("06-12-2016").getTime() - TimeUnit.DAYS.toMillis(tenantCount);
        for (int tenantIndex = 0; tenantIndex < tenantCount; tenantIndex++) {
            TenantId tenantId = new TenantId(Uuids.startOf(ts));
            ts += TimeUnit.DAYS.toMillis(1) + random.nextInt(1000);
            for (int queueIndex = 0; queueIndex < queueCount; queueIndex++) {
                QueueKey queueKey = new QueueKey(ServiceType.TB_RULE_ENGINE, "queue" + queueIndex, tenantId);
                for (int partition = 0; partition < partitionCount; partition++) {
                    ServiceInfo serviceInfo = clusterRoutingService.resolveByPartitionIdx(services, queueKey, partition);
                    String serviceId = serviceInfo.getServiceId();
                    map.put(serviceId, map.get(serviceId) + 1);
                }
            }
        }

        checkDispersion(start, map, tenantCount * queueCount * partitionCount, 10.0);
    }

    private <T> void checkDispersion(long start, Map<T, Integer> map, int iterations, double maxDiffPercent) {
        List<Map.Entry<T, Integer>> data = map.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getValue)).collect(Collectors.toList());
        long end = System.currentTimeMillis();
        double ideal = ((double) iterations) / map.size();
        double diff = Math.max(data.get(data.size() - 1).getValue() - ideal, ideal - data.get(0).getValue());
        double diffPercent = (diff / ideal) * 100.0;
        System.out.println("Time: " + (end - start) + " Diff: " + diff + "(" + String.format("%f", diffPercent) + "%)");
        for (Map.Entry<T, Integer> entry : data) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }
        Assert.assertTrue(diffPercent < maxDiffPercent);
    }

    @Test
    public void testPartitionsAssignmentWithDedicatedServers() {
        int isolatedProfilesCount = 5;
        int tenantsCountPerProfile = 100;
        int dedicatedServerSetsCount = 3;
        int serversCountPerSet = 3;
        int profilesPerSet = (int) Math.ceil((double) isolatedProfilesCount / dedicatedServerSetsCount);

        List<TenantProfileId> isolatedTenantProfiles = Stream.generate(() -> new TenantProfileId(UUID.randomUUID()))
                .limit(isolatedProfilesCount).collect(Collectors.toList());
        Map<TenantId, TenantProfileId> tenants = new HashMap<>();
        for (TenantProfileId tenantProfileId : isolatedTenantProfiles) {
            for (int i = 0; i < tenantsCountPerProfile; i++) {
                tenants.put(new TenantId(UUID.randomUUID()), tenantProfileId);
            }
        }

        List<Queue> queues = new ArrayList<>();
        queues.add(createQueue(TenantId.SYS_TENANT_ID, 10));
        tenants.forEach((tenantId, profileId) -> {
            queues.add(createQueue(tenantId, 2));
            mockRoutingInfo(tenantId, profileId, true);
        });
        mockQueues(queues);

        List<ServiceInfo> ruleEngines = new ArrayList<>();
        Map<TenantProfileId, List<ServiceInfo>> dedicatedServers = new HashMap<>();
        int serviceId = 0;
        for (int i = 0; i < serversCountPerSet; i++) {
            ServiceInfo commonServer = ServiceInfo.newBuilder()
                    .setServiceId("tb-rule-engine-" + serviceId)
                    .addAllServiceTypes(List.of(ServiceType.TB_RULE_ENGINE.name()))
                    .build();
            ruleEngines.add(commonServer);
            serviceId++;
        }
        for (int i = 0; i < dedicatedServerSetsCount; i++) {
            List<TenantProfileId> assignedProfiles = ListUtils.partition(isolatedTenantProfiles, profilesPerSet).get(i);
            for (int j = 0; j < serversCountPerSet; j++) {
                ServiceInfo dedicatedServer = ServiceInfo.newBuilder()
                        .setServiceId("tb-rule-engine-" + serviceId)
                        .addAllServiceTypes(List.of(ServiceType.TB_RULE_ENGINE.name()))
                        .addAllAssignedTenantProfiles(assignedProfiles.stream().map(UUIDBased::toString).collect(Collectors.toList()))
                        .build();
                ruleEngines.add(dedicatedServer);
                serviceId++;

                for (TenantProfileId assignedProfileId : assignedProfiles) {
                    dedicatedServers.computeIfAbsent(assignedProfileId, p -> new ArrayList<>()).add(dedicatedServer);
                }
            }
        }

        Map<QueueKey, Map<ServiceInfo, List<Integer>>> serversPartitions = new HashMap<>();
        clusterRoutingService.init();
        for (ServiceInfo ruleEngine : ruleEngines) {
            List<ServiceInfo> other = new ArrayList<>(ruleEngines);
            other.removeIf(serviceInfo -> serviceInfo.getServiceId().equals(ruleEngine.getServiceId()));

            clusterRoutingService.recalculatePartitions(ruleEngine, other);
            clusterRoutingService.myPartitions.forEach((queueKey, partitions) -> {
                serversPartitions.computeIfAbsent(queueKey, k -> new HashMap<>()).put(ruleEngine, partitions);
            });
        }
        assertThat(serversPartitions.keySet()).containsAll(queues.stream().map(queue -> new QueueKey(ServiceType.TB_RULE_ENGINE, queue)).collect(Collectors.toList()));

        serversPartitions.forEach((queueKey, partitionsPerServer) -> {
            if (queueKey.getTenantId().isSysTenantId()) {
                partitionsPerServer.forEach((server, partitions) -> {
                    assertThat(server.getAssignedTenantProfilesCount()).as("system queues are not assigned to dedicated servers").isZero();
                });
            } else {
                List<ServiceInfo> responsibleServers = dedicatedServers.get(tenants.get(queueKey.getTenantId()));
                partitionsPerServer.forEach((server, partitions) -> {
                    assertThat(server.getAssignedTenantProfilesCount()).as("isolated queues are only assigned to dedicated servers").isPositive();
                    assertThat(responsibleServers).contains(server);
                });
            }

            List<Integer> allPartitions = partitionsPerServer.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            assertThat(allPartitions).doesNotHaveDuplicates();
        });
    }

    @Test
    public void testPartitionChangeEvents_isolatedProfile_oneCommonServer_oneDedicated() {
        ServiceInfo commonRuleEngine = ServiceInfo.newBuilder()
                .setServiceId("tb-rule-engine-1")
                .addAllServiceTypes(List.of(ServiceType.TB_RULE_ENGINE.name()))
                .build();
        TenantProfileId tenantProfileId = new TenantProfileId(UUID.randomUUID());
        ServiceInfo dedicatedRuleEngine = ServiceInfo.newBuilder()
                .setServiceId("tb-rule-engine-isolated-1")
                .addAllServiceTypes(List.of(ServiceType.TB_RULE_ENGINE.name()))
                .addAssignedTenantProfiles(tenantProfileId.toString())
                .build();

        List<Queue> queues = new ArrayList<>();
        Queue systemQueue = createQueue(TenantId.SYS_TENANT_ID, 10);
        queues.add(systemQueue);

        TenantId tenantId = new TenantId(UUID.randomUUID());
        mockRoutingInfo(tenantId, tenantProfileId, false); // not isolated yet
        mockQueues(queues);

        when(discoveryService.isService(eq(ServiceType.TB_RULE_ENGINE))).thenReturn(true);
        Mockito.reset(applicationEventPublisher);
        HashPartitionService partitionService_common = createPartitionService();
        partitionService_common.recalculatePartitions(commonRuleEngine, List.of(dedicatedRuleEngine));
        verifyPartitionChangeEvent(event -> {
            return event.getQueueKey().getTenantId().isSysTenantId() &&
                    event.getQueueKey().getQueueName().equals(DataConstants.MAIN_QUEUE_NAME) &&
                    event.getPartitions().stream().map(TopicPartitionInfo::getPartition).collect(Collectors.toSet())
                            .size() == systemQueue.getPartitions();
        });

        Mockito.reset(applicationEventPublisher);
        HashPartitionService partitionService_dedicated = createPartitionService();
        partitionService_dedicated.recalculatePartitions(dedicatedRuleEngine, List.of(commonRuleEngine));
        verify(applicationEventPublisher, never()).publishEvent(any(PartitionChangeEvent.class));


        Queue isolatedQueue = createQueue(tenantId, 3);
        queues.add(isolatedQueue);
        mockQueues(queues);
        mockRoutingInfo(tenantId, tenantProfileId, true); // making isolated
        TransportProtos.QueueUpdateMsg queueUpdateMsg = TransportProtos.QueueUpdateMsg.newBuilder()
                .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                .setQueueIdMSB(isolatedQueue.getUuidId().getMostSignificantBits())
                .setQueueIdLSB(isolatedQueue.getUuidId().getLeastSignificantBits())
                .setQueueName(isolatedQueue.getName())
                .setQueueTopic(isolatedQueue.getTopic())
                .setPartitions(isolatedQueue.getPartitions())
                .build();

        partitionService_common.updateQueue(queueUpdateMsg);
        partitionService_common.recalculatePartitions(commonRuleEngine, List.of(dedicatedRuleEngine));
        // expecting event about no partitions for isolated queue key
        verifyPartitionChangeEvent(event -> {
            return event.getQueueKey().getTenantId().equals(tenantId) &&
                    event.getQueueKey().getQueueName().equals(DataConstants.MAIN_QUEUE_NAME) &&
                    event.getPartitions().isEmpty();
        });

        partitionService_dedicated.updateQueue(queueUpdateMsg);
        partitionService_dedicated.recalculatePartitions(dedicatedRuleEngine, List.of(commonRuleEngine));
        verifyPartitionChangeEvent(event -> {
            return event.getQueueKey().getTenantId().equals(tenantId) &&
                    event.getQueueKey().getQueueName().equals(DataConstants.MAIN_QUEUE_NAME) &&
                    event.getPartitions().stream().map(TopicPartitionInfo::getPartition).collect(Collectors.toSet())
                            .size() == isolatedQueue.getPartitions();
        });


        queues = List.of(systemQueue);
        mockQueues(queues);
        mockRoutingInfo(tenantId, tenantProfileId, false); // turning off isolation
        Mockito.reset(applicationEventPublisher);
        TransportProtos.QueueDeleteMsg queueDeleteMsg = TransportProtos.QueueDeleteMsg.newBuilder()
                .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                .setQueueIdMSB(isolatedQueue.getUuidId().getMostSignificantBits())
                .setQueueIdLSB(isolatedQueue.getUuidId().getLeastSignificantBits())
                .setQueueName(isolatedQueue.getName())
                .build();
        partitionService_dedicated.removeQueue(queueDeleteMsg);
        partitionService_dedicated.recalculatePartitions(dedicatedRuleEngine, List.of(commonRuleEngine));
        verifyPartitionChangeEvent(event -> {
            return event.getQueueKey().getTenantId().equals(tenantId) &&
                    event.getQueueKey().getQueueName().equals(DataConstants.MAIN_QUEUE_NAME) &&
                    event.getPartitions().isEmpty();
        });
    }

    @Test
    public void testIsManagedByCurrentServiceCheck() {
        TenantProfileId isolatedProfileId = new TenantProfileId(UUID.randomUUID());
        when(discoveryService.getAssignedTenantProfiles()).thenReturn(Set.of(isolatedProfileId.getId())); // dedicated server
        TenantProfileId regularProfileId = new TenantProfileId(UUID.randomUUID());

        TenantId isolatedTenantId = new TenantId(UUID.randomUUID());
        mockRoutingInfo(isolatedTenantId, isolatedProfileId, true);
        TenantId regularTenantId = new TenantId(UUID.randomUUID());
        mockRoutingInfo(regularTenantId, regularProfileId, false);

        assertThat(clusterRoutingService.isManagedByCurrentService(isolatedTenantId)).isTrue();
        assertThat(clusterRoutingService.isManagedByCurrentService(regularTenantId)).isFalse();


        when(discoveryService.getAssignedTenantProfiles()).thenReturn(Collections.emptySet()); // common server

        assertThat(clusterRoutingService.isManagedByCurrentService(isolatedTenantId)).isTrue();
        assertThat(clusterRoutingService.isManagedByCurrentService(regularTenantId)).isTrue();
    }

    private void verifyPartitionChangeEvent(Predicate<PartitionChangeEvent> predicate) {
        verify(applicationEventPublisher).publishEvent(argThat(event -> event instanceof PartitionChangeEvent && predicate.test((PartitionChangeEvent) event)));
    }

    private void mockRoutingInfo(TenantId tenantId, TenantProfileId tenantProfileId, boolean isolatedTbRuleEngine) {
        when(routingInfoService.getRoutingInfo(eq(tenantId)))
                .thenReturn(new TenantRoutingInfo(tenantId, tenantProfileId, isolatedTbRuleEngine));
    }

    private void mockQueues(List<Queue> queues) {
        when(queueRoutingInfoService.getAllQueuesRoutingInfo()).thenReturn(queues.stream()
                .map(QueueRoutingInfo::new).collect(Collectors.toList()));
    }

    private Queue createQueue(TenantId tenantId, int partitions) {
        Queue systemQueue = new Queue();
        systemQueue.setTenantId(tenantId);
        systemQueue.setName("Main");
        systemQueue.setTopic(DataConstants.MAIN_QUEUE_TOPIC);
        systemQueue.setPartitions(partitions);
        systemQueue.setId(new QueueId(UUID.randomUUID()));
        return systemQueue;
    }

    private HashPartitionService createPartitionService() {
        HashPartitionService partitionService = new HashPartitionService(discoveryService,
                routingInfoService,
                applicationEventPublisher,
                queueRoutingInfoService);
        ReflectionTestUtils.setField(partitionService, "coreTopic", "tb.core");
        ReflectionTestUtils.setField(partitionService, "corePartitions", 10);
        ReflectionTestUtils.setField(partitionService, "vcTopic", "tb.vc");
        ReflectionTestUtils.setField(partitionService, "vcPartitions", 10);
        ReflectionTestUtils.setField(partitionService, "hashFunctionName", hashFunctionName);
        partitionService.init();
        partitionService.partitionsInit();
        return partitionService;
    }

}
