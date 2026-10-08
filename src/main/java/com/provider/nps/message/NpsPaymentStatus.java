package com.provider.nps.message;

/**
 * Payment status classification; a queried status alone does not establish
 * finality.
 */
public enum NpsPaymentStatus {
    ACTC, ACCP, ACSP, ACSC, PDNG, RJCT, UNKNOWN;

    public static NpsPaymentStatus fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public boolean isSuccessful() {
        return this == ACSP || this == ACSC;
    }

    public boolean isPending() {
        return this == ACTC || this == ACCP || this == PDNG;
    }

    public boolean isRejected() {
        return this == RJCT;
    }
}
