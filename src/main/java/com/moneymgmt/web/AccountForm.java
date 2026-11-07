package com.moneymgmt.web;

import com.moneymgmt.domain.Account;
import com.moneymgmt.domain.AccountType;
import com.moneymgmt.util.Money;
import java.util.LinkedHashMap;
import java.util.Map;

/** Add / edit an account. The balance is only asked for a new account; later balances use "Update balances". */
public class AccountForm {

    private Long id;
    private String name;
    private AccountType type = AccountType.BANK;
    private String balance;
    private String note;
    private boolean active = true;

    public static AccountForm of(Account a) {
        AccountForm f = new AccountForm();
        f.id = a.getId();
        f.name = a.getName();
        f.type = a.getType();
        f.note = a.getNote();
        f.active = a.isActive();
        return f;
    }

    public Map<String, String> validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "acc.error.name");
        } else if (name.trim().length() > 100) {
            errors.put("name", "fixed.error.tooLong");
        }
        if (type == null) {
            errors.put("type", "form.error.invalid");
        }
        if (id == null) {
            var b = Money.parse(balance);
            if (b == null) {
                errors.put("balance", "acc.error.balance");
            }
        }
        if (note != null && note.length() > 300) {
            errors.put("note", "fixed.error.tooLong");
        }
        return errors;
    }

    public String cleanNote() {
        return note == null || note.isBlank() ? null : note.trim();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public String getBalance() { return balance; }
    public void setBalance(String balance) { this.balance = balance; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
