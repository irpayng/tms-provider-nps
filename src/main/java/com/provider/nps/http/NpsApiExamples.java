package com.provider.nps.http;

/** Synthetic values for Swagger's editable request bodies. */
final class NpsApiExamples {
    private NpsApiExamples() {
    }
    static final String NAME_ENQUIRY = """
            {"reference":"enquiry-test-001","metadata":{
              "account_number":"0123456789","account_name":"JOHN DOE","bank_code":"000013"
            }}
            """;
    static final String TRANSFER = """
            {"action":"bank-transfer","reference":"transfer-test-001","amount":100.50,"metadata":{
              "account_number":"0123456789","account_name":"JOHN DOE","bank_code":"000013",
              "debtor_name":"JANE DOE","debtor_account":"9876543210",
              "debtor_account_designation":"2","debtor_account_tier":"3",
              "debtor_id_type":"BVN","debtor_id_value":"22123456789",
              "creditor_account_designation":"2","creditor_account_tier":"3",
              "creditor_id_type":"BVN","creditor_id_value":"22987654321",
              "name_enquiry_msg_id":"99905720261018090000123456789012345",
              "channel_code":"1","transaction_location":"01080652440N020900337921E",
              "narration":"Sandbox test transfer"
            }}
            """;
    static final String REQUERY = """
            {"reference":"requery-test-001","metadata":{
              "original_msg_id":"99905720261018090000123456789012345",
              "original_tx_id":"99905720261018090000123456789012345",
              "original_creation_time":"2026-10-18T09:00:00Z","original_settlement_date":"2026-10-18",
              "original_instructing_agent":"999057","original_instructed_agent":"000013"
            }}
            """;
}
