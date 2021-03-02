package io.mycat.sqlhandler.dml;

import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import io.mycat.MycatDataContext;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.Response;
import io.vertx.core.Future;


import static io.mycat.sqlhandler.dml.UpdateSQLHandler.updateHandler;


public class ReplaceSQLHandler extends AbstractSQLHandler<SQLReplaceStatement> {

    @Override
    protected Future<Void> onExecute(SQLRequest<SQLReplaceStatement> request, MycatDataContext dataContext, Response response) {
        SQLExprTableSource tableSource = request.getAst().getTableSource();
        return updateHandler(request.getAst(),dataContext,tableSource,response);
    }
}
