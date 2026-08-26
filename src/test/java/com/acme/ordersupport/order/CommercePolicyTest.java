package com.acme.ordersupport.order;

import static com.acme.ordersupport.testsupport.Fixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.order.model.CommercePolicy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommercePolicyTest {

    @Nested
    @DisplayName("return window")
    class ReturnWindow {

        @Test
        void furnitureOnlyOrdersGetTheExtendedWindow() {
            assertThat(CommercePolicy.returnWindowDays(List.of(
                    line("DESK-1", "FURNITURE", true, "300.00"),
                    line("CHAIR-1", "FURNITURE", true, "459.00"))))
                    .isEqualTo(CommercePolicy.FURNITURE_RETURN_WINDOW_DAYS);
        }

        @Test
        void oneNonFurnitureLineDropsTheWholeOrderToTheStandardWindow() {
            assertThat(CommercePolicy.returnWindowDays(List.of(
                    line("DESK-1", "FURNITURE", true, "300.00"),
                    line("HP-1", "AUDIO", true, "89.99"))))
                    .isEqualTo(CommercePolicy.STANDARD_RETURN_WINDOW_DAYS);
        }

        @Test
        void anEmptyLineListFallsBackToTheStandardWindowRatherThanTheGenerousOne() {
            assertThat(CommercePolicy.returnWindowDays(List.of()))
                    .isEqualTo(CommercePolicy.STANDARD_RETURN_WINDOW_DAYS);
        }
    }

    @Nested
    @DisplayName("who pays for return shipping")
    class Fault {

        @ParameterizedTest
        @ValueSource(strings = {
                "It arrived damaged",
                "The screen is cracked",
                "DEFECTIVE out of the box",
                "you sent the wrong item",
                "there's a missing part in the box",
                "it stopped working after a day"})
        void faultsAndDamageWaiveTheLabelFee(String reason) {
            assertThat(CommercePolicy.sellerAtFault(reason)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "changed my mind",
                "bought the wrong colour by accident",
                "found it cheaper elsewhere",
                "no longer needed"})
        void changeOfMindDoesNot(String reason) {
            assertThat(CommercePolicy.sellerAtFault(reason)).isFalse();
        }

        @Test
        void aMissingReasonIsTreatedAsChangeOfMind() {
            assertThat(CommercePolicy.sellerAtFault(null)).isFalse();
            assertThat(CommercePolicy.sellerAtFault("   ")).isFalse();
        }
    }
}
