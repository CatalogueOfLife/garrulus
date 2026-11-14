package org.catalogueoflife.nametracker.store.type;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** Maps Postgres text[] to List&lt;String&gt;. Registered per column via typeHandler= in the mapper XML. */
public class StringArrayTypeHandler extends BaseTypeHandler<List<String>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setArray(i, ps.getConnection().createArrayOf("text", parameter.toArray(new String[0])));
  }

  @Override
  public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getArray(columnName));
  }

  @Override
  public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getArray(columnIndex));
  }

  @Override
  public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getArray(columnIndex));
  }

  private static List<String> toList(Array array) throws SQLException {
    return array == null ? null : new ArrayList<>(Arrays.asList((String[]) array.getArray()));
  }
}
