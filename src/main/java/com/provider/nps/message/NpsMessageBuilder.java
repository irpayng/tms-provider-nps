package com.provider.nps.message;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.provider.nps.crypto.NpsIdGenerator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Base class for NPS ISO 20022 message builders.
 * Provides common header building functionality.
 */
@ApplicationScoped
public class NpsMessageBuilder {

    protected static final String HEAD_NS = "urn:iso:std:iso:20022:tech:xsd:head.001.001.02";
    protected static final String ACMT_023_NS = "urn:iso:std:iso:20022:tech:xsd:acmt.023.001.03";
    protected static final String PACS_008_NS = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    protected static final String PAIN_001_NS = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";
    protected static final String PACS_028_NS = "urn:iso:std:iso:20022:tech:xsd:pacs.028.001.04";

    @ConfigProperty(name = "nps.source-bic")
    String sourceBic;

    @ConfigProperty(name = "nps.source-name")
    String sourceName;

    @ConfigProperty(name = "nps.source-id")
    String sourceId;

    @Inject
    NpsIdGenerator idGenerator;

    /**
     * Builds the ISO 20022 Business Application Header (AppHdr).
     * 
     * @param msgDefIdr Message definition identifier (e.g., "acmt.023.001.03")
     * @param bizMsgIdr Business message identifier
     * @param toBic Destination BIC
     * @return AppHdr XML fragment
     */
    protected String buildAppHdr(String msgDefIdr, String bizMsgIdr, String toBic) {
        String creDate = idGenerator.currentTimestamp();

        return String.format("""
                <AppHdr xmlns="%s">
                    <Fr>
                        <FIId>
                            <FinInstnId>
                                <BICFI>%s</BICFI>
                            </FinInstnId>
                        </FIId>
                    </Fr>
                    <To>
                        <FIId>
                            <FinInstnId>
                                <BICFI>%s</BICFI>
                            </FinInstnId>
                        </FIId>
                    </To>
                    <BizMsgIdr>%s</BizMsgIdr>
                    <MsgDefIdr>%s</MsgDefIdr>
                    <CreDt>%s</CreDt>
                </AppHdr>""",
                HEAD_NS, sourceBic, toBic, bizMsgIdr, msgDefIdr, creDate);
    }

    /**
     * Wraps content in BizMsg envelope.
     */
    protected String wrapInBizMsg(String appHdr, String document) {
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <BizMsg xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
                %s
                %s
                </BizMsg>""", appHdr, document);
    }

    public NpsIdGenerator getIdGenerator() {
        return idGenerator;
    }

    public String getSourceBic() {
        return sourceBic;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getSourceId() {
        return sourceId;
    }
}
