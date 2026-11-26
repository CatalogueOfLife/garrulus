package org.catalogueoflife.garrulus.store.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Binds a List&lt;Float&gt; as one float4[] parameter ({@code = ANY(...)}), so the bind count does not grow with the list. Write only. */
public class FloatListTypeHandler extends BaseTypeHandler<List<Float>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<Float> parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setArray(i, ps.getConnection().createArrayOf("float4", parameter.toArray(new Float[0])));
  }

  @Override
  public List<Float> getNullableResult(ResultSet rs, String columnName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Float> getNullableResult(ResultSet rs, int columnIndex) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Float> getNullableResult(CallableStatement cs, int columnIndex) {
    throw new UnsupportedOperationException();
  }
}
