/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.daomonitor.metrics.p2p;

import bisq.core.btc.wallet.BtcWalletService;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.P2PServiceListener;
import bisq.network.p2p.network.CloseConnectionReason;
import bisq.network.p2p.network.Connection;
import bisq.network.p2p.network.ConnectionListener;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.seed.SeedNodeRepository;
import bisq.network.p2p.storage.P2PDataStorage;

import bisq.common.Timer;
import bisq.common.UserThread;

import javax.inject.Inject;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;



import bisq.daomonitor.metrics.DaoMetrics;
import bisq.daomonitor.metrics.DaoMetricsModel;

@Slf4j
public class DaoMonitorRequestManager implements ConnectionListener {
    private static final long RETRY_DELAY_SEC = 30;
    private static final long CLEANUP_TIMER = 60;
    private static final long REQUEST_PERIOD_MIN = 10;
    private static final int MAX_RETRIES = 5;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final int numNodes;

    private P2PDataStorage dataStorage;
    private SeedNodeRepository seedNodeRepository;
    private DaoMetricsModel daoMetricsModel;
    private final BtcWalletService btcWalletService;
    private final Set<NodeAddress> seedNodeAddresses;

    private final Map<NodeAddress, DaoMonitorP2PDataRequestHandler> p2pDataHandlerMap = new HashMap<>();
    private final Map<NodeAddress, DaoMonitorBlockRequestHandler> blocksHandlerMap = new HashMap<>();

    private Map<NodeAddress, Timer> p2pDataRetryTimerMap = new HashMap<>();
    private Map<NodeAddress, Timer> blocksRetryTimerMap = new HashMap<>();

    private Map<NodeAddress, Integer> p2pDataRetryCounterMap = new HashMap<>();
    private Map<NodeAddress, Integer> blocksRetryCounterMap = new HashMap<>();
    private boolean stopped;
    private int completedP2PDataRequestIndex;
    private int completedBlocksRequestIndex;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public DaoMonitorRequestManager(NetworkNode networkNode,
                                    P2PService p2PService,
                                    P2PDataStorage dataStorage,
                                    SeedNodeRepository seedNodeRepository,
                                    DaoMetricsModel daoMetricsModel,
                                    BtcWalletService btcWalletService) {
        this.networkNode = networkNode;
        this.dataStorage = dataStorage;
        this.seedNodeRepository = seedNodeRepository;
        this.daoMetricsModel = daoMetricsModel;
        this.btcWalletService = btcWalletService;

        this.networkNode.addConnectionListener(this);

        seedNodeAddresses = new HashSet<>(seedNodeRepository.getSeedNodeAddresses());
        seedNodeAddresses.forEach(nodeAddress -> daoMetricsModel.addToP2PDataMap(nodeAddress, new DaoMetrics()));
        seedNodeAddresses.forEach(nodeAddress -> daoMetricsModel.addToBlockDataMap(nodeAddress, new DaoMetrics()));
        numNodes = seedNodeAddresses.size();

        p2PService.addP2PServiceListener(new P2PServiceListener() {
            @Override
            public void onDataReceived() {
            }

            @Override
            public void onNoSeedNodeAvailable() {
            }

            @Override
            public void onNoPeersAvailable() {
            }

            @Override
            public void onUpdatedDataReceived() {
            }

            @Override
            public void onTorNodeReady() {
            }

            @Override
            public void onHiddenServicePublished() {
                start();
            }

            @Override
            public void onSetupFailed(Throwable throwable) {
            }

            @Override
            public void onRequestCustomBridges() {
            }
        });
    }

    public void shutDown() {
        stopped = true;
        stopAllRetryTimers();
        networkNode.removeConnectionListener(this);
        closeAllHandlers();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void start() {
        requestAllNodes();
        UserThread.runPeriodically(this::requestAllNodes, REQUEST_PERIOD_MIN, TimeUnit.MINUTES);

        // We want to update the data for the btc nodes more frequently
        UserThread.runPeriodically(daoMetricsModel::updateReport, 10);
    }

    private void requestAllNodes() {
        stopAllRetryTimers();
        //closeAllConnections();
        // we give 1 sec. for all connection shutdown
        final int[] delay = {100};
        daoMetricsModel.setLastCheckTs(System.currentTimeMillis());

        seedNodeAddresses.stream().forEach(nodeAddress -> {
            UserThread.runAfter(() -> requestFromNode(nodeAddress), delay[0], TimeUnit.MILLISECONDS);
            delay[0] += 100;
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
    }

    @Override
    public void onDisconnect(CloseConnectionReason closeConnectionReason, Connection connection) {
        closeHandler(connection);
    }

    @Override
    public void onError(Throwable throwable) {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // RequestData
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void requestFromNode(NodeAddress nodeAddress) {
        requestP2PDataFromNode(nodeAddress);
        UserThread.runAfter(() -> requestBlocksFromNode(nodeAddress), 30);
    }

    private void requestBlocksFromNode(NodeAddress nodeAddress) {
        if (!stopped) {
            if (!blocksHandlerMap.containsKey(nodeAddress)) {
                DaoMetrics blockMetrics = daoMetricsModel.getBlockMetrics(nodeAddress);
                DaoMonitorBlockRequestHandler requestDataHandler = new DaoMonitorBlockRequestHandler(networkNode,
                        blockMetrics,
                        btcWalletService,
                        new DaoMonitorBlockRequestHandler.Listener() {
                            @Override
                            public void onComplete() {
                                log.trace("RequestDataHandshake of outbound connection complete. nodeAddress={}",
                                        nodeAddress);
                                stopP2PDataRetryTimer(nodeAddress);
                                blocksRetryCounterMap.remove(nodeAddress);
                                blockMetrics.setNumRequestAttempts(blocksRetryCounterMap.getOrDefault(nodeAddress, 1));

                                // need to remove before listeners are notified as they cause the update call
                                blocksHandlerMap.remove(nodeAddress);

                                daoMetricsModel.updateReport();
                                completedBlocksRequestIndex++;
                                if (completedBlocksRequestIndex == numNodes)
                                    daoMetricsModel.log();

                                if (daoMetricsModel.getNodesInError().contains(nodeAddress)) {
                                    daoMetricsModel.removeNodesInError(nodeAddress);
                                }
                            }

                            @Override
                            public void onFault(String errorMessage, NodeAddress nodeAddress) {
                                blocksHandlerMap.remove(nodeAddress);
                                stopBlocksRetryTimer(nodeAddress);

                                int retryCounter = blocksRetryCounterMap.getOrDefault(nodeAddress, 0);
                                blockMetrics.setNumRequestAttempts(retryCounter);
                                if (retryCounter < MAX_RETRIES) {
                                    log.info("We got an error at peer={}. We will try again after a delay of {} sec. error={} ",
                                            nodeAddress, RETRY_DELAY_SEC, errorMessage);
                                    final Timer timer = UserThread.runAfter(() -> requestBlocksFromNode(nodeAddress), RETRY_DELAY_SEC);
                                    blocksRetryTimerMap.put(nodeAddress, timer);
                                    blocksRetryCounterMap.put(nodeAddress, ++retryCounter);
                                } else {
                                    log.warn("We got repeated errors at peer={}. error={} ",
                                            nodeAddress, errorMessage);

                                    daoMetricsModel.addNodesInError(nodeAddress);
                                    blockMetrics.getErrorMessages().add(errorMessage + " (" + new Date().toString() + ")");

                                    daoMetricsModel.updateReport();
                                    completedBlocksRequestIndex++;
                                    if (completedBlocksRequestIndex == numNodes)
                                        daoMetricsModel.log();

                                    blocksRetryCounterMap.remove(nodeAddress);
                                }
                            }
                        });
                blocksHandlerMap.put(nodeAddress, requestDataHandler);
                requestDataHandler.requestData(nodeAddress);
            } else {
                log.warn("We have started already a requestDataHandshake to peer. nodeAddress=" + nodeAddress + "\n" +
                        "We start a cleanup timer if the handler has not closed by itself in between 2 minutes.");

                UserThread.runAfter(() -> {
                    if (blocksHandlerMap.containsKey(nodeAddress)) {
                        DaoMonitorBlockRequestHandler handler = blocksHandlerMap.get(nodeAddress);
                        handler.stop();
                        blocksHandlerMap.remove(nodeAddress);
                    }
                }, CLEANUP_TIMER);
            }
        } else {
            log.warn("We have stopped already. We ignore that requestData call.");
        }
    }

    private void requestP2PDataFromNode(NodeAddress nodeAddress) {
        if (!stopped) {
            if (!p2pDataHandlerMap.containsKey(nodeAddress)) {
                final DaoMetrics p2PDataMetrics = daoMetricsModel.getP2PDataMetrics(nodeAddress);
                DaoMonitorP2PDataRequestHandler requestDataHandler = new DaoMonitorP2PDataRequestHandler(networkNode,
                        dataStorage,
                        p2PDataMetrics,
                        new DaoMonitorP2PDataRequestHandler.Listener() {
                            @Override
                            public void onComplete() {
                                log.trace("RequestDataHandshake of outbound connection complete. nodeAddress={}",
                                        nodeAddress);
                                stopP2PDataRetryTimer(nodeAddress);
                                p2pDataRetryCounterMap.remove(nodeAddress);
                                p2PDataMetrics.setNumRequestAttempts(p2pDataRetryCounterMap.getOrDefault(nodeAddress, 1));

                                // need to remove before listeners are notified as they cause the update call
                                p2pDataHandlerMap.remove(nodeAddress);

                                daoMetricsModel.updateReport();
                                completedP2PDataRequestIndex++;
                                if (completedP2PDataRequestIndex == numNodes)
                                    daoMetricsModel.log();

                                if (daoMetricsModel.getNodesInError().contains(nodeAddress)) {
                                    daoMetricsModel.removeNodesInError(nodeAddress);
                                }
                            }

                            @Override
                            public void onFault(String errorMessage, NodeAddress nodeAddress) {
                                p2pDataHandlerMap.remove(nodeAddress);
                                stopP2PDataRetryTimer(nodeAddress);

                                int retryCounter = p2pDataRetryCounterMap.getOrDefault(nodeAddress, 0);
                                p2PDataMetrics.setNumRequestAttempts(retryCounter);
                                if (retryCounter < MAX_RETRIES) {
                                    log.info("We got an error at peer={}. We will try again after a delay of {} sec. error={} ",
                                            nodeAddress, RETRY_DELAY_SEC, errorMessage);
                                    final Timer timer = UserThread.runAfter(() -> requestP2PDataFromNode(nodeAddress), RETRY_DELAY_SEC);
                                    p2pDataRetryTimerMap.put(nodeAddress, timer);
                                    p2pDataRetryCounterMap.put(nodeAddress, ++retryCounter);
                                } else {
                                    log.warn("We got repeated errors at peer={}. error={} ",
                                            nodeAddress, errorMessage);

                                    daoMetricsModel.addNodesInError(nodeAddress);
                                    p2PDataMetrics.getErrorMessages().add(errorMessage + " (" + new Date().toString() + ")");

                                    daoMetricsModel.updateReport();
                                    completedP2PDataRequestIndex++;
                                    if (completedP2PDataRequestIndex == numNodes)
                                        daoMetricsModel.log();

                                    p2pDataRetryCounterMap.remove(nodeAddress);
                                }
                            }
                        });
                p2pDataHandlerMap.put(nodeAddress, requestDataHandler);
                requestDataHandler.requestData(nodeAddress);
            } else {
                log.warn("We have started already a requestDataHandshake to peer. nodeAddress=" + nodeAddress + "\n" +
                        "We start a cleanup timer if the handler has not closed by itself in between 2 minutes.");

                UserThread.runAfter(() -> {
                    if (p2pDataHandlerMap.containsKey(nodeAddress)) {
                        DaoMonitorP2PDataRequestHandler handler = p2pDataHandlerMap.get(nodeAddress);
                        handler.stop();
                        p2pDataHandlerMap.remove(nodeAddress);
                    }
                }, CLEANUP_TIMER);
            }
        } else {
            log.warn("We have stopped already. We ignore that requestData call.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void closeAllConnections() {
        networkNode.getAllConnections().forEach(connection -> connection.shutDown(CloseConnectionReason.CLOSE_REQUESTED_BY_PEER));
    }

    private void stopAllRetryTimers() {
        p2pDataRetryTimerMap.values().forEach(Timer::stop);
        p2pDataRetryTimerMap.clear();

        p2pDataRetryCounterMap.clear();

        blocksRetryTimerMap.values().forEach(Timer::stop);
        blocksRetryTimerMap.clear();

        blocksRetryCounterMap.clear();
    }

    private void closeAllHandlers() {
        p2pDataHandlerMap.values().forEach(DaoMonitorP2PDataRequestHandler::cancel);
        p2pDataHandlerMap.clear();

        blocksHandlerMap.values().forEach(DaoMonitorBlockRequestHandler::cancel);
        blocksHandlerMap.clear();
    }

    private void stopP2PDataRetryTimer(NodeAddress nodeAddress) {
        p2pDataRetryTimerMap.entrySet().stream()
                .filter(e -> e.getKey().equals(nodeAddress))
                .forEach(e -> e.getValue().stop());
        p2pDataRetryTimerMap.remove(nodeAddress);
    }

    private void stopBlocksRetryTimer(NodeAddress nodeAddress) {
        blocksRetryTimerMap.entrySet().stream()
                .filter(e -> e.getKey().equals(nodeAddress))
                .forEach(e -> e.getValue().stop());
        blocksRetryTimerMap.remove(nodeAddress);
    }

    private void closeHandler(Connection connection) {
        Optional<NodeAddress> peersNodeAddressOptional = connection.getPeersNodeAddressOptional();
        if (peersNodeAddressOptional.isPresent()) {
            NodeAddress nodeAddress = peersNodeAddressOptional.get();
            if (p2pDataHandlerMap.containsKey(nodeAddress)) {
                p2pDataHandlerMap.get(nodeAddress).cancel();
                p2pDataHandlerMap.remove(nodeAddress);
            }

            if (blocksHandlerMap.containsKey(nodeAddress)) {
                blocksHandlerMap.get(nodeAddress).cancel();
                blocksHandlerMap.remove(nodeAddress);
            }
        } else {
            log.trace("closeRequestDataHandler: nodeAddress not set in connection " + connection);
        }
    }
}
