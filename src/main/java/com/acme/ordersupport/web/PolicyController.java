package com.acme.ordersupport.web;

import com.acme.ordersupport.rag.PolicyRetriever;
import com.acme.ordersupport.rag.RetrievedPolicy;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval on its own, with the scores exposed.
 *
 * <p>Useful for tuning topK and the similarity threshold against real questions,
 * and it makes the RAG half of the system demonstrable without a chat model or an
 * API key.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyRetriever retriever;

    public PolicyController(PolicyRetriever retriever) {
        this.retriever = retriever;
    }

    @GetMapping("/search")
    public List<RetrievedPolicy> search(@RequestParam("q") String query,
                                        @RequestParam(value = "topK", defaultValue = "4") int topK,
                                        @RequestParam(value = "threshold", defaultValue = "0.0")
                                        double threshold) {
        return retriever.retrieve(query, topK, threshold);
    }
}
