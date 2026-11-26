package org.catalogueoflife.garrulus.store.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Binds a List&lt;Long&gt; as one bigint[] parameter ({@code = ANY(...)}), so the bind count does not grow with the list. Write only. */
public class LongListTypeHandler extends BaseTypeHandler<List<Long>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setArray(i, ps.getConnection().createArrayOf("bigint", parameter.toArray(new Long[0])));
  }

  @Override
  public List<Long> getNullableResult(ResultSet rs, String columnName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Long> getNullableResult(ResultSet rs, int columnIndex) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Long> getNullableResult(CallableStatement cs, int columnIndex) {
    throw new UnsupportedOperationException();
  }
}
