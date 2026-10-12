package org.simulator.validator;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation expectations for a request/response exchange. Stored as JSON in test cases
 * ({@code test_case.validation_json}) or as reusable profiles.
 */
public class ValidationProfile {

    private String name = "default";
    /** Expected DE39, supports alternatives separated by '|', e.g. "00|10". Null = do not check. */
    private String expectedResponseCode;
    /** Fields that the response must echo from the request when present in the request. */
    private List<Integer> echoFields = new ArrayList<>(List.of(2, 3, 4, 11, 37, 41));
    /** Response must be the request MTI + 10. */
    private boolean checkResponseMti = true;
    /** Check type/length of every field against the dialect. */
    private boolean structural = true;
    private List<FieldRule> requestRules = new ArrayList<>();
    private List<FieldRule> responseRules = new ArrayList<>();

    public static ValidationProfile expecting(String rc) {
        ValidationProfile p = new ValidationProfile();
        p.setExpectedResponseCode(rc);
        return p;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getExpectedResponseCode() { return expectedResponseCode; }
    public void setExpectedResponseCode(String expectedResponseCode) { this.expectedResponseCode = expectedResponseCode; }
    public List<Integer> getEchoFields() { return echoFields; }
    public void setEchoFields(List<Integer> echoFields) { this.echoFields = echoFields; }
    public boolean isCheckResponseMti() { return checkResponseMti; }
    public void setCheckResponseMti(boolean checkResponseMti) { this.checkResponseMti = checkResponseMti; }
    public boolean isStructural() { return structural; }
    public void setStructural(boolean structural) { this.structural = structural; }
    public List<FieldRule> getRequestRules() { return requestRules; }
    public void setRequestRules(List<FieldRule> requestRules) { this.requestRules = requestRules; }
    public List<FieldRule> getResponseRules() { return responseRules; }
    public void setResponseRules(List<FieldRule> responseRules) { this.responseRules = responseRules; }
}
