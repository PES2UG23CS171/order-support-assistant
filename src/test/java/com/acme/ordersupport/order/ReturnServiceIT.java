package com.acme.ordersupport.order;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.model.RefundStatus;
import com.acme.ordersupport.order.model.ReturnResult;
import com.acme.ordersupport.order.model.ReturnStatus;
import com.acme.ordersupport.testsupport.DatabaseFixtures;
import com.acme.ordersupport.testsupport.IntegrationTest;
import com.acme.ordersupport.testsupport.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ReturnServiceIT extends PostgresSupport {

    @Autowired
    private ReturnService returnService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private DatabaseFixtures fixtures;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    void aRecentlyDeliveredOrderOpensAReturnAndAPendingRefund() {
        ReturnResult result = returnService.initiateReturn(ADA, "ORD-1003", "it arrived damaged");

        assertThat(result.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(result.rmaNumber()).matches("RMA-[0-9A-F]{8}");
        assertThat(result.deductedFees()).isEqualByComparingTo("0.00");
        assertThat(fixtures.statusOf("ORD-1003")).isEqualTo("RETURN_REQUESTED");

        assertThat(refundService.refundsForOrder(ADA, "ORD-1003")).singleElement()
                .satisfies(refund -> {
                    assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
                    assertThat(refund.amount()).isEqualByComparingTo("89.99");
                    // No date is promised until the item is back and inspected.
                    assertThat(refund.expectedSettlementDate()).isNull();
                });
    }

    @Test
    void theSecondReturnOnAnOrderIsRefusedByTheOpenRmaRule() {
        returnService.initiateReturn(ADA, "ORD-1003", "it arrived damaged");

        // Exercises ReturnRepository.findOpenByOrder against real rows, which is the
        // half the unit test had to stub. It also pins the check order: opening a
        // return moves the order to RETURN_REQUESTED, and the answer to the second
        // request must be "one is already open", not "this is not delivered yet".
        var second = returnService.assessReturn(ADA, "ORD-1003");
        assertThat(second.code()).isEqualTo("RETURN_ALREADY_OPEN");
        assertThat(second.explanation()).contains("already has an open return");
    }

    @Test
    void twoOrdersDeliveredTheSameDaySplitOnCategoryAlone() {
        // ORD-1004 (furniture) and ORD-1007 (audio) were both delivered 45 days ago.
        // The only thing that separates them is the extended furniture window.
        assertThat(returnService.assessReturn(ADA, "ORD-1004").allowed()).isTrue();

        var expired = returnService.assessReturn(ADA, "ORD-1007");
        assertThat(expired.allowed()).isFalse();
        assertThat(expired.code()).isEqualTo("RETURN_WINDOW_EXPIRED");
        assertThat(expired.explanation()).contains("45 days ago").contains("30 days");
    }

    @Test
    void aGiftCardOrderHasNothingReturnableOnIt() {
        assertThat(returnService.assessReturn(ADA, "ORD-1005").code()).isEqualTo("NO_RETURNABLE_ITEMS");

        assertThatThrownBy(() -> returnService.initiateReturn(ADA, "ORD-1005", "changed my mind"))
                .isInstanceOf(ActionNotAllowedException.class);
        assertThat(fixtures.statusOf("ORD-1005")).isEqualTo("DELIVERED");
    }

    @Test
    void anOrderStillInTransitCannotBeReturnedYet() {
        assertThat(returnService.assessReturn(ADA, "ORD-1002").code()).isEqualTo("ORDER_NOT_DELIVERED");
    }

    @Test
    void aChangeOfMindReturnDeductsTheLabelFeeFromTheRefundRow() {
        ReturnResult result = returnService.initiateReturn(ADA, "ORD-1003", "changed my mind");

        assertThat(result.deductedFees()).isEqualByComparingTo("6.95");
        assertThat(refundService.refundsForOrder(ADA, "ORD-1003")).singleElement()
                .satisfies(refund -> assertThat(refund.amount()).isEqualByComparingTo("83.04"));
    }
}
