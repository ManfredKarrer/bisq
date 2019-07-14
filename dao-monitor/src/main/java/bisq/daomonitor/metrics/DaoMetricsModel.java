/*
 * This file is part of Bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.daomonitor.metrics;

import bisq.core.network.p2p.seed.DefaultSeedNodeRepository;

import bisq.network.p2p.NodeAddress;

import bisq.common.util.MathUtils;

import javax.inject.Inject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DaoMetricsModel {
    private final DateFormat dateFormat = new SimpleDateFormat("MMMMM dd, HH:mm:ss");
    @Getter
    private String resultAsString;
    @Getter
    private String resultAsHtml;
    @Setter
    private long lastCheckTs;
    private long btcNodeUptimeTs;
    private int totalErrors = 0;
    private Map<NodeAddress, DaoMetrics> p2pDataMap = new HashMap<>();
    private Map<NodeAddress, DaoMetrics> blocksMap = new HashMap<>();
    @Getter
    private Set<NodeAddress> nodesInError = new HashSet<>();
    private final Map<String, String> operatorMap = new HashMap<>();

    @Inject
    public DaoMetricsModel() {
    }

    public void addToP2PDataMap(NodeAddress nodeAddress, DaoMetrics daoMetrics) {
        p2pDataMap.put(nodeAddress, daoMetrics);
    }

    public DaoMetrics getP2PDataMetrics(NodeAddress nodeAddress) {
        return p2pDataMap.get(nodeAddress);
    }

    public void addToBlockDataMap(NodeAddress nodeAddress, DaoMetrics daoMetrics) {
        blocksMap.put(nodeAddress, daoMetrics);
    }

    public DaoMetrics getBlockMetrics(NodeAddress nodeAddress) {
        return blocksMap.get(nodeAddress);
    }

    public void updateReport() {
        if (operatorMap.isEmpty()) {
            InputStream fileInputStream = DefaultSeedNodeRepository.class.getClassLoader().getResourceAsStream("btc_mainnet.seednodes");
            BufferedReader seedNodeFile = new BufferedReader(new InputStreamReader(fileInputStream));
            seedNodeFile.lines().forEach(line -> {
                if (!line.startsWith("#")) {
                    String[] strings = line.split(" \\(@");
                    String node = strings[0];
                    String operator = strings[1];
                    operatorMap.put(node, operator);
                }
            });
        }

        if (btcNodeUptimeTs == 0)
            btcNodeUptimeTs = new Date().getTime();

        Map<String, Double> accumulatedValues = new HashMap<>();
        final double[] items = {0};

        Map<NodeAddress, DaoMetrics> mergedMap = new HashMap<>();
        blocksMap.forEach((node, value) -> {
            DaoMetrics metrics = new DaoMetrics(p2pDataMap.get(node));
            mergedMap.put(node, metrics);
            List<Map<String, Integer>> fromBlockRequest = value.getReceivedObjectsList();
            List<Map<String, Integer>> fromP2PRequest = metrics.getReceivedObjectsList();
            fromP2PRequest.forEach(objects -> {
                fromBlockRequest.forEach(e1 -> {
                    e1.forEach(objects::put);
                });
            });
        });

        List<Map.Entry<NodeAddress, DaoMetrics>> entryList = new ArrayList<>(mergedMap.entrySet());
        entryList.sort(Comparator.comparing(o -> o.getKey().getFullAddress()));

        totalErrors = 0;
        entryList.forEach(e -> {
            totalErrors += e.getValue().errorMessages.stream().filter(s -> !s.isEmpty()).count();
            final List<Map<String, Integer>> receivedObjectsList = e.getValue().getReceivedObjectsList();
            if (!receivedObjectsList.isEmpty()) {
                items[0] += 1;
                Map<String, Integer> last = receivedObjectsList.get(receivedObjectsList.size() - 1);
                last.entrySet().stream().forEach(e2 -> {
                    int accuValue = e2.getValue();
                    if (accumulatedValues.containsKey(e2.getKey()))
                        accuValue += accumulatedValues.get(e2.getKey());

                    accumulatedValues.put(e2.getKey(), (double) accuValue);
                });
            }
        });

        Map<String, Double> averageValues = new HashMap<>();
        accumulatedValues.entrySet().stream().forEach(e -> {
            averageValues.put(e.getKey(), e.getValue() / items[0]);
        });

        Calendar calendar = new GregorianCalendar();
        calendar.setTimeZone(TimeZone.getTimeZone("CET"));
        calendar.setTimeInMillis(lastCheckTs);
        String time = calendar.getTime().toString();

        StringBuilder html = new StringBuilder();
        html.append("<html>" +
                "<head>" +
                "<style>table, th, td {border: 1px solid black;}</style>" +
                "</head>" +
                "<body>" +
                "<h3>")
                .append("Seed nodes in error: <b>" + totalErrors + "</b><br/>" +
                        "Last check started at: " + time + "<br/></h3>" +
                        "<table style=\"width:100%\">" +
                        "<tr>" +
                        "<th align=\"left\">Operator</th>" +
                        "<th align=\"left\">Node address</th>" +
                        "<th align=\"left\">Total num requests</th>" +
                        "<th align=\"left\">Total num errors</th>" +
                        "<th align=\"left\">Last request</th>" +
                        "<th align=\"left\">Last response</th>" +
                        "<th align=\"left\">RRT average</th>" +
                        "<th align=\"left\">Num requests (retries)</th>" +
                        "<th align=\"left\">Last error message</th>" +
                        "<th align=\"left\">Last data</th>" +
                        "<th align=\"left\">Data deviation last request</th>" +
                        "</tr>");

        StringBuilder sb = new StringBuilder();
        sb.append("Seed nodes in error:" + totalErrors);
        sb.append("\nLast check started at: " + time + "\n");

        entryList.forEach(e -> {
            final DaoMetrics daoMetrics = e.getValue();
            final List<Long> allDurations = daoMetrics.getRequestDurations();
            final String allDurationsString = allDurations.stream().map(Object::toString).collect(Collectors.joining("<br/>"));
            final OptionalDouble averageOptional = allDurations.stream().mapToLong(value -> value).average();
            double durationAverage = 0;
            if (averageOptional.isPresent())
                durationAverage = averageOptional.getAsDouble() / 1000;
            final NodeAddress nodeAddress = e.getKey();
            final String operator = operatorMap.get(nodeAddress.getFullAddress());
            final List<String> errorMessages = daoMetrics.getErrorMessages();
            final int numErrors = (int) errorMessages.stream().filter(s -> !s.isEmpty()).count();
            int numRequests = allDurations.size();
            String lastErrorMsg = "";
            int lastIndexOfError = 0;
            for (int i = 0; i < errorMessages.size(); i++) {
                final String msg = errorMessages.get(i);
                if (!msg.isEmpty()) {
                    lastIndexOfError = i;
                    lastErrorMsg = "Error at request " + lastIndexOfError + ":" + msg;
                }
            }

            //  String lastErrorMsg = numErrors > 0 ? errorMessages.get(errorMessages.size() - 1) : "";
            final List<Map<String, Integer>> allReceivedData = daoMetrics.getReceivedObjectsList();
            Map<String, Integer> lastReceivedData = !allReceivedData.isEmpty() ? allReceivedData.get(allReceivedData.size() - 1) : new HashMap<>();
            final String lastReceivedDataString = lastReceivedData.entrySet().stream().map(Object::toString).collect(Collectors.joining("<br/>"));
            final String allReceivedDataString = allReceivedData.stream().map(Object::toString).collect(Collectors.joining("<br/>"));
            final String requestTs = daoMetrics.getLastDataRequestTs() > 0 ? dateFormat.format(new Date(daoMetrics.getLastDataRequestTs())) : "" + "<br/>";
            final String responseTs = daoMetrics.getLastDataResponseTs() > 0 ? dateFormat.format(new Date(daoMetrics.getLastDataResponseTs())) : "" + "<br/>";
            final String numRequestAttempts = daoMetrics.getNumRequestAttempts() + "<br/>";

            sb.append("\nOperator: ").append(operator)
                    .append("\nNode address: ").append(nodeAddress)
                    .append("\nTotal num requests: ").append(numRequests)
                    .append("\nTotal num errors: ").append(numErrors)
                    .append("\nLast request: ").append(requestTs)
                    .append("\nLast response: ").append(responseTs)
                    .append("\nRRT average: ").append(durationAverage)
                    .append("\nNum requests (retries): ").append(numRequestAttempts)
                    .append("\nLast error message: ").append(lastErrorMsg)
                    .append("\nLast data: ").append(lastReceivedDataString);

            String colorNumErrors = lastIndexOfError == numErrors ? "black" : "red";
            String colorDurationAverage = durationAverage < 30 ? "black" : "red";
            html.append("<tr>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + operator + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + nodeAddress + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + numRequests + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + numErrors + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + requestTs + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + responseTs + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorDurationAverage + "\">" + durationAverage + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + numRequestAttempts + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + lastErrorMsg + "</font> ").append("</td>")
                    .append("<td>").append(lastReceivedDataString).append("</td><td>");

            if (!allReceivedData.isEmpty()) {
                sb.append("\nData deviation last request:\n");
                lastReceivedData.entrySet().stream().forEach(e2 -> {
                    final String dataItem = e2.getKey();
                    double deviation = MathUtils.roundDouble((double) e2.getValue() / averageValues.get(dataItem) * 100, 2);
                    String str = dataItem + ": " + deviation + "%";
                    sb.append(str).append("\n");
                    String color;
                    final double devAbs = Math.abs(deviation - 100);
                    if (devAbs < 5)
                        color = "black";
                    else if (devAbs < 10)
                        color = "blue";
                    else
                        color = "red";

                    html.append("<font color=\"" + color + "\">" + str + "</font>").append("<br/>");
                });
                sb.append("Duration all requests: ").append(allDurationsString)
                        .append("\nAll data: ").append(allReceivedDataString).append("\n");

                html.append("</td></tr>");
            }
        });
        html.append("</table></body></html>");

        resultAsString = sb.toString();
        resultAsHtml = html.toString();
    }

    public void log() {
        log.info("\n\n#################################################################\n" +
                resultAsString +
                "#################################################################\n\n");
    }

    public void addNodesInError(NodeAddress nodeAddress) {
        nodesInError.add(nodeAddress);
    }

    public void removeNodesInError(NodeAddress nodeAddress) {
        nodesInError.remove(nodeAddress);
    }
}
