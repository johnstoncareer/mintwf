package com.intwfs.mintwf.core.api;

import com.intwfs.mintwf.core.runtime.InMemoryProcessStore;
import com.intwfs.mintwf.core.spi.ProcessStore;

class InMemoryProcessEngineTest extends ProcessEngineContractTest {

    @Override
    protected ProcessStore createStore() {
        return new InMemoryProcessStore();
    }
}
