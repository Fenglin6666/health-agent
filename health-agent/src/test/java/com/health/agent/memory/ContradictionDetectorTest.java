package com.health.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContradictionDetectorTest {

    private final ContradictionDetector detector = new ContradictionDetector();
    private final FactLedger ledger = new InMemoryFactLedger();

    @Test
    void same_fact_in_different_words_is_not_a_contradiction() {
        assertTrue(ContradictionDetector.equivalent("50μg 每日一次", "每天50微克"));
        assertTrue(ContradictionDetector.equivalent("五年", "5 年"));
        assertTrue(ContradictionDetector.equivalent("每天50微克", "每天 50 微克"));
    }

    @Test
    void different_numbers_are_always_a_change() {
        // 文本再像也不能放过：剂量变了就是真的变了
        assertFalse(ContradictionDetector.equivalent("每天50微克", "每天100微克"));
        assertFalse(ContradictionDetector.equivalent("五年", "3 年"));
    }

    @Test
    void allergy_flip_is_high_severity() {
        ledger.append("u1", FactType.ALLERGY, "青霉素", "皮试阳性", 0.9, "", "test");

        List<Contradiction> found = detector.detect("u1", FactType.ALLERGY, "青霉素", "否认", ledger);

        assertEquals(1, found.size());
        assertEquals(Contradiction.Kind.ALLERGY_FLIP, found.get(0).kind());
        assertEquals(Contradiction.Severity.HIGH, found.get(0).severity());
    }

    @Test
    void no_contradiction_when_value_unchanged() {
        ledger.append("u1", FactType.CHRONIC, "甲减", "五年", 0.9, "", "test");

        assertTrue(detector.detect("u1", FactType.CHRONIC, "甲减", "5 年", ledger).isEmpty());
    }

    @Test
    void medication_restart_after_stop_is_flagged() {
        ledger.append("u1", FactType.MEDICATION, "优甲乐", "已停药", 0.9, "", "test");

        List<Contradiction> found = detector.detect("u1", FactType.MEDICATION, "优甲乐", "每天50微克", ledger);

        assertEquals(1, found.size());
        assertEquals(Contradiction.Kind.MEDICATION_RESTART, found.get(0).kind());
    }

    @Test
    void stopping_medication_is_not_a_contradiction() {
        ledger.append("u1", FactType.MEDICATION, "优甲乐", "每天50微克", 0.9, "", "test");

        assertTrue(detector.detect("u1", FactType.MEDICATION, "优甲乐", "已停药", ledger).isEmpty());
    }
}
