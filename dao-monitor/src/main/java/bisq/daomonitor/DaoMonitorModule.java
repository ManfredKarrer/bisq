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

package bisq.daomonitor;

import bisq.core.app.BisqEnvironment;
import bisq.core.app.misc.ModuleForAppWithP2p;

import bisq.network.p2p.P2PModule;

import org.springframework.core.env.Environment;

import static com.google.inject.name.Names.named;



import bisq.daomonitor.metrics.p2p.DaoMonitorP2PModule;

class DaoMonitorModule extends ModuleForAppWithP2p {

    public DaoMonitorModule(Environment environment) {
        super(environment);
    }

    @Override
    protected void configure() {
        super.configure();

        bindConstant().annotatedWith(named(DaoMonitorOptionKeys.SLACK_URL_SEED_CHANNEL)).to(environment.getRequiredProperty(DaoMonitorOptionKeys.SLACK_URL_SEED_CHANNEL));
        bindConstant().annotatedWith(named(DaoMonitorOptionKeys.SLACK_BTC_SEED_CHANNEL)).to(environment.getRequiredProperty(DaoMonitorOptionKeys.SLACK_BTC_SEED_CHANNEL));
        bindConstant().annotatedWith(named(DaoMonitorOptionKeys.SLACK_PROVIDER_SEED_CHANNEL)).to(environment.getRequiredProperty(DaoMonitorOptionKeys.SLACK_PROVIDER_SEED_CHANNEL));
        bindConstant().annotatedWith(named(DaoMonitorOptionKeys.PORT)).to(environment.getRequiredProperty(DaoMonitorOptionKeys.PORT));
    }

    @Override
    protected void configEnvironment() {
        bind(BisqEnvironment.class).toInstance((DaoMonitorEnvironment) environment);
    }

    @Override
    protected P2PModule p2pModule() {
        return new DaoMonitorP2PModule(environment);
    }
}
