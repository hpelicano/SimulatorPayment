package org.simulator.common.util;

public final class Luhn {

    private Luhn() {
    }

    public static boolean isValid(String number) {
        if (number == null || number.length() < 2 || !number.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return checkDigit(number.substring(0, number.length() - 1)) == number.charAt(number.length() - 1) - '0';
    }

    public static int checkDigit(String partial) {
        int sum = 0;
        boolean dbl = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int d = partial.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** Completes a PAN prefix (BIN + account digits) with a valid check digit. */
    public static String complete(String partial) {
        return partial + checkDigit(partial);
    }
}
