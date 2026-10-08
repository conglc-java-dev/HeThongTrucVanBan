UPDATE audit_logs audit
SET document_id = COALESCE(audit.document_id, transaction.document_id),
    transaction_code = transaction.transaction_code,
    master_transaction_code = transaction.master_transaction_code,
    sender_org_id = COALESCE(audit.sender_org_id, transaction.sender_org_id),
    receiver_org_id = COALESCE(audit.receiver_org_id, transaction.receiver_org_id)
FROM exchange_transactions transaction
WHERE audit.transaction_id = transaction.id
  AND (audit.document_id IS NULL
       OR audit.transaction_code IS NULL
       OR audit.master_transaction_code IS NULL
       OR audit.sender_org_id IS NULL
       OR audit.receiver_org_id IS NULL);

UPDATE audit_logs audit
SET document_code = document.document_code
FROM documents document
WHERE audit.document_id = document.id
  AND audit.document_code IS NULL;
