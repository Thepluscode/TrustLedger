package com.trustledger.support;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public class CloseContextAfterTestClassListener extends AbstractTestExecutionListener {

    @Override
    public void afterTestClass(TestContext testContext) {
        if (testContext.hasApplicationContext()) {
            testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
        }
    }
}
