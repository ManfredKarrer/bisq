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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
public class DaoMetrics {
    List<Long> requestDurations = new ArrayList<>();
    List<String> errorMessages = new ArrayList<>();
    List<Map<String, Integer>> receivedObjectsList = new ArrayList<>();
    @Setter
    long lastDataRequestTs;
    @Setter
    long lastDataResponseTs;
    @Setter
    long numRequestAttempts;

    public DaoMetrics() {
    }

    public DaoMetrics(DaoMetrics daoMetrics) {
        this.requestDurations = new ArrayList<>(daoMetrics.requestDurations);
        this.errorMessages = new ArrayList<>(daoMetrics.errorMessages);
        this.receivedObjectsList = new ArrayList<>(daoMetrics.receivedObjectsList);
        this.lastDataRequestTs = daoMetrics.lastDataRequestTs;
        this.lastDataResponseTs = daoMetrics.lastDataResponseTs;
        this.numRequestAttempts = daoMetrics.numRequestAttempts;
    }

    @Override
    public String toString() {
        return "DaoMetrics{" +
                "\n     requestDurations=" + requestDurations +
                ",\n     errorMessages=" + errorMessages +
                ",\n     receivedObjectsList=" + receivedObjectsList +
                ",\n     lastDataRequestTs=" + lastDataRequestTs +
                ",\n     lastDataResponseTs=" + lastDataResponseTs +
                ",\n     numRequestAttempts=" + numRequestAttempts +
                "\n}";
    }
}
