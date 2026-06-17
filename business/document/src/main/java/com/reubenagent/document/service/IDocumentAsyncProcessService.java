package com.reubenagent.document.service;

public interface IDocumentAsyncProcessService {

    void handleParseStrategyRoute(Long documentId, Long taskId);

}
