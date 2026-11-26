package org.catalogueoflife.garrulus.store.task;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Maps task.priority (smallint) to {@link TaskPriority} by its code. */
public class TaskPriorityTypeHandler extends BaseTypeHandler<TaskPriority> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, TaskPriority parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setInt(i, parameter.code());
  }

  @Override
  public TaskPriority getNullableResult(ResultSet rs, String columnName) throws SQLException {
    int v = rs.getInt(columnName);
    return rs.wasNull() ? null : TaskPriority.fromCode(v);
  }

  @Override
  public TaskPriority getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    int v = rs.getInt(columnIndex);
    return rs.wasNull() ? null : TaskPriority.fromCode(v);
  }

  @Override
  public TaskPriority getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    int v = cs.getInt(columnIndex);
    return cs.wasNull() ? null : TaskPriority.fromCode(v);
  }
}
