package com.provider.nps.message;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builder for pacs.028.001.04 - FIToFIPaymentStatusRequest.
 * Used for transaction status query (requery).
 * 
 * This is used to check the status of a previously sent transaction
 * when no response was received or status is unclear.
 */
@ApplicationScoped
public class Pacs028Builder {

    private static final String NIBSS_BIC = "ABORNGLA";

    @Inject
    NpsMessageBuilder baseBuilder;

    /**
     * Builds a pacs.028 payment status request.
     * 
     * @param originalMsgId The MsgId of the original transaction
     * @param originalEndToEndId The EndToEndId of the original transaction
     * @return Complete ISO 20022 XML message
     */
    public String build(String originalMsgId, String originalEndToEndId) {
        String msgId = baseBuilder.getIdGenerator().generateMessageId();
        String creDate = baseBuilder.getIdGenerator().currentTimestamp();

        String appHdr = baseBuilder.buildAppHdr("pacs.028.001.04", msgId, NIBSS_BIC);

        String document = buildDocument(msgId, creDate, originalMsgId, originalEndToEndId);

        return baseBuilder.wrapInBizMsg(appHdr, document);
    }

    /**
     * Builds a payment status request with full details.
     */
    public String build(PaymentStatusRequest request) {
        return build(request.getOriginalMsgId(), request.getOriginalEndToEndId());
    }

    private String buildDocument(String msgId, String creDate, String originalMsgId, 
            String originalEndToEndId) {

        return String.format("""
                <Document xmlns="%s">
                    <FIToFIPmtStsReq>
                        <GrpHdr>
                            <MsgId>%s</MsgId>
                            <CreDtTm>%s</CreDtTm>
                        </GrpHdr>
                        <OrgnlGrpInf>
                            <OrgnlMsgId>%s</OrgnlMsgId>
                            <OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>
                        </OrgnlGrpInf>
                        <TxInf>
                            <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                            <InstgAgt>
                                <FinInstnId>
                                    <BICFI>%s</BICFI>
                                </FinInstnId>
                            </InstgAgt>
                        </TxInf>
                    </FIToFIPmtStsReq>
                </Document>""",
                NpsMessageBuilder.PACS_028_NS,
                msgId,
                creDate,
                originalMsgId,
                originalEndToEndId,
                baseBuilder.getSourceBic());
    }

    /**
     * Request data class for payment status query.
     */
    public static class PaymentStatusRequest {
        private String originalMsgId;
        private String originalEndToEndId;
        private String originalTxId;

        public String getOriginalMsgId() {
            return originalMsgId;
        }

        public void setOriginalMsgId(String originalMsgId) {
            this.originalMsgId = originalMsgId;
        }

        public String getOriginalEndToEndId() {
            return originalEndToEndId;
        }

        public void setOriginalEndToEndId(String originalEndToEndId) {
            this.originalEndToEndId = originalEndToEndId;
        }

        public String getOriginalTxId() {
            return originalTxId;
        }

        public void setOriginalTxId(String originalTxId) {
            this.originalTxId = originalTxId;
        }
    }
}
