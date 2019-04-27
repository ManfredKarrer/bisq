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

package bisq.desktop.components.paymentmethods;

import bisq.core.account.score.AccountScoreService;
import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.payload.PaymentAccountPayload;
import bisq.core.util.BSFormatter;
import bisq.core.util.validation.InputValidator;

import javafx.scene.layout.GridPane;

public class NationalBankForm extends BankForm {
    public static int addFormForBuyer(GridPane gridPane, int gridRow, PaymentAccountPayload paymentAccountPayload) {
        return BankForm.addFormForBuyer(gridPane, gridRow, paymentAccountPayload);
    }

    public NationalBankForm(PaymentAccount paymentAccount, AccountAgeWitnessService accountAgeWitnessService, AccountScoreService accountScoreService, InputValidator inputValidator,
                            GridPane gridPane, int gridRow, BSFormatter formatter) {
        super(paymentAccount, accountAgeWitnessService, accountScoreService, inputValidator, gridPane, gridRow, formatter);
    }
}
