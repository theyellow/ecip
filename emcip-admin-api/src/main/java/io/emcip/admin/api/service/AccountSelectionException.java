package io.emcip.admin.api.service;

import io.emcip.admin.api.controller.FlagController;
import java.util.List;
import lombok.Getter;

@Getter
public class AccountSelectionException extends RuntimeException {

    private final List<FlagController.AccountOption> accounts;

    public AccountSelectionException(List<FlagController.AccountOption> accounts) {
        super("Multiple accounts watch this chat — selection required");
        this.accounts = accounts;
    }
}
