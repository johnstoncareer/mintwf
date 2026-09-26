package com.intwfs.mintwf.store.jdbc;

import com.intwfs.mintwf.core.api.ProcessEngineContractTest;
import com.intwfs.mintwf.core.spi.ProcessStore;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;

class JdbcProcessEngineTest extends ProcessEngineContractTest {

    @Override
    protected ProcessStore createStore() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcProcessStore(dataSource);
    }
}
