package org.simulator.config.model;

/**
 * Keys and options for the software HSM. Key values are hex strings and may be stored
 * encrypted as {@code ENC(...)}. Test keys only: never load production keys in a simulator.
 */
public class CryptoConfig {

    private boolean enabled;
    /** Zone PIN key (2/3-key TDES) used to encrypt DE52. */
    private String zpk;
    /** MAC key: TDES for X9.19/ALG1, AES for AES_CMAC. */
    private String mak;
    /** Card verification key pair (KA||KB, 32 hex). */
    private String cvk;
    /** Issuer master key for application cryptograms (IMK-AC). */
    private String imkAc;
    /** NONE, X919, ISO9797_ALG1, AES_CMAC. */
    private String macAlgorithm = "NONE";
    /** ISO 9564 PIN block format: 0, 1 or 3. */
    private int pinBlockFormat = 0;
    /** NONE (CVN10 style, MK used directly) or EMV_CSK (EMV common session key). */
    private String emvSessionKey = "NONE";
    /** Which part of 9F10 enters the ARQC: CVR (Visa CVN10), FULL or NONE. */
    private String emvIadMode = "CVR";
    private boolean generateArqc = true;
    private boolean generateArpc = true;
    private boolean verifyArqc = true;
    /** Response code forced when the ARQC does not verify; empty keeps the rule's code. */
    private String arqcFailureResponseCode = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getZpk() { return zpk; }
    public void setZpk(String zpk) { this.zpk = zpk; }
    public String getMak() { return mak; }
    public void setMak(String mak) { this.mak = mak; }
    public String getCvk() { return cvk; }
    public void setCvk(String cvk) { this.cvk = cvk; }
    public String getImkAc() { return imkAc; }
    public void setImkAc(String imkAc) { this.imkAc = imkAc; }
    public String getMacAlgorithm() { return macAlgorithm; }
    public void setMacAlgorithm(String macAlgorithm) { this.macAlgorithm = macAlgorithm; }
    public int getPinBlockFormat() { return pinBlockFormat; }
    public void setPinBlockFormat(int pinBlockFormat) { this.pinBlockFormat = pinBlockFormat; }
    public String getEmvSessionKey() { return emvSessionKey; }
    public void setEmvSessionKey(String emvSessionKey) { this.emvSessionKey = emvSessionKey; }
    public String getEmvIadMode() { return emvIadMode; }
    public void setEmvIadMode(String emvIadMode) { this.emvIadMode = emvIadMode; }
    public boolean isGenerateArqc() { return generateArqc; }
    public void setGenerateArqc(boolean generateArqc) { this.generateArqc = generateArqc; }
    public boolean isGenerateArpc() { return generateArpc; }
    public void setGenerateArpc(boolean generateArpc) { this.generateArpc = generateArpc; }
    public boolean isVerifyArqc() { return verifyArqc; }
    public void setVerifyArqc(boolean verifyArqc) { this.verifyArqc = verifyArqc; }
    public String getArqcFailureResponseCode() { return arqcFailureResponseCode; }
    public void setArqcFailureResponseCode(String code) { this.arqcFailureResponseCode = code; }
}
