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

package bisq.desktop.main.overlays.windows;

import bisq.desktop.components.InputTextField;
import bisq.desktop.main.overlays.Overlay;
import bisq.desktop.util.FormBuilder;
import bisq.desktop.util.validation.EmailValidator;

import bisq.core.locale.Res;
import bisq.core.payment.SepaAccount;
import bisq.core.user.User;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AddEmailToAccountWindow extends Overlay<AddEmailToAccountWindow> {
    private final List<SepaAccount> sepaAccounts;
    private final User user;
    private InputTextField email;
    private EmailValidator emailValidator;

    public AddEmailToAccountWindow(List<SepaAccount> sepaAccounts, User user) {
        this.sepaAccounts = sepaAccounts;
        this.user = user;
        type = Type.Attention;
        width = 900;
        emailValidator = new EmailValidator();
    }

    @Override
    public void show() {
        if (gridPane != null) {
            rowIndex = -1;
            gridPane.getChildren().clear();
        }

        if (headLine == null)
            headLine = Res.get("addEmailToAccountWindow.headline");

        createGridPane();
        addHeadLine();
        addContent();
        addButtons();
        applyStyles();
        display();
    }

    private void addContent() {
        FormBuilder.addMultilineLabel(gridPane, ++rowIndex, Res.get("addEmailToAccountWindow.info"));
        email = FormBuilder.addTopLabelInputTextField(gridPane, ++rowIndex, Res.get("payment.email")).second;
        email.setValidator(emailValidator);
        email.textProperty().addListener((observable, oldValue, newValue) -> actionButton.setDisable(!emailValidator.validate(newValue).isValid));
    }

    @Override
    protected void addButtons() {
        actionButtonText(Res.get("addEmailToAccountWindow.button"));
        onAction(() -> {
            sepaAccounts.forEach(sepaAccount -> {
                sepaAccount.setEmail(email.getText());
                user.removePaymentAccount(sepaAccount);
                user.addPaymentAccount(sepaAccount);
            });

            hide();
        });

        super.addButtons();
        actionButton.setDisable(true);
    }
}
