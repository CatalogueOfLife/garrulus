package org.catalogueoflife.nametracker.store.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Binds a List&lt;Boolean&gt; as one bool[] parameter ({@code = ANY(...)}), so the bind count does not grow with the list. Write only. */
public class BooleanListTypeHandler extends BaseTypeHandler<List<Boolean>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<Boolean> parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setArray(i, ps.getConnection().createArrayOf("bool", parameter.toArray(new Boolean[0])));
  }

  @Override
  public List<Boolean> getNullableResult(ResultSet rs, String columnName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Boolean> getNullableResult(ResultSet rs, int columnIndex) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Boolean> getNullableResult(CallableStatement cs, int columnIndex) {
    throw new UnsupportedOperationException();
  }
}
