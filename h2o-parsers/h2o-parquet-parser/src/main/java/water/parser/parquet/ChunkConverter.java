package water.parser.parquet;

import org.apache.parquet.column.Dictionary;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import water.fvec.Vec;
import water.parser.BufferedString;
import water.parser.ParseWriter;
import water.util.StringUtils;

/**
 * Implementation of Parquet's GroupConverter for H2O's chunks.
 *
 * ChunkConverter is responsible for converting parquet data into Chunks. As opposed to regular
 * Parquet converters this converter doesn't actually produce any records and instead writes the data
 * using a provided ParseWriter to chunks. The (artificial) output of the converter is number of
 * the record that was written to the chunk.
 *
 * Note: It is meant to be used as a root converter.
 */
class ChunkConverter extends GroupConverter {

  private final WriterDelegate _writer;
  private final Converter[] _converters;

  private int _currentRecordIdx = -1;

  ChunkConverter(MessageType parquetSchema, byte[] chunkSchema, ParseWriter writer) {
    _writer = new WriterDelegate(writer, chunkSchema.length);
    int colIdx = 0;
    _converters = new Converter[chunkSchema.length];
    for (Type parquetField : parquetSchema.getFields()) {
      assert parquetField.isPrimitive();
      _converters[colIdx] = newConverter(colIdx, chunkSchema[colIdx], parquetField.asPrimitiveType());
      colIdx++;
    }
  }

  @Override
  public Converter getConverter(int fieldIndex) {
    return _converters[fieldIndex];
  }

  @Override
  public void start() {
    _currentRecordIdx++;
    _writer.startLine();
  }

  @Override
  public void end() {
    _writer.endLine();
    assert _writer.lineNum() - 1 == _currentRecordIdx;
  }

  int getCurrentRecordIdx() {
    return _currentRecordIdx;
  }

  private PrimitiveConverter newConverter(int colIdx, byte vecType, PrimitiveType parquetType) {
    switch (vecType) {
      case Vec.T_BAD:
      case Vec.T_CAT:
      case Vec.T_STR:
      case Vec.T_UUID:
      case Vec.T_TIME:
        if (parquetType.getOriginalType() == OriginalType.TIMESTAMP_MILLIS) {
          return new TimestampConverter(colIdx, _writer);
        } else {
          boolean dictSupport = parquetType.getOriginalType() == OriginalType.UTF8 || parquetType.getOriginalType() == OriginalType.ENUM;
          return new StringConverter(_writer, colIdx, dictSupport);
        }
      case Vec.T_NUM:
        return new NumberConverter(colIdx, _writer);
      default:
        throw new UnsupportedOperationException("Unsupported type " + vecType);
    }
  }

  private static class StringConverter extends PrimitiveConverter {

    private final BufferedString _bs = new BufferedString();
    private final int _colIdx;
    private final WriterDelegate _writer;
    private final boolean _dictionarySupport;
    private String[] _dict;

    StringConverter(WriterDelegate writer, int colIdx, boolean dictionarySupport) {
      _colIdx = colIdx;
      _writer = writer;
      _dictionarySupport = dictionarySupport;
    }

    @Override
    public void addBinary(Binary value) {
      _bs.set(StringUtils.bytesOf(value.toStringUsingUTF8()));
      _writer.addStrCol(_colIdx, _bs);
    }

    @Override
    public boolean hasDictionarySupport() {
      return _dictionarySupport;
    }

    @Override
    public void setDictionary(Dictionary dictionary) {
      _dict = new String[dictionary.getMaxId() + 1];
      for (int i = 0; i <= dictionary.getMaxId(); i++) {
        _dict[i] = dictionary.decodeToBinary(i).toStringUsingUTF8();
      }
    }

    @Override
    public void addValueFromDictionary(int dictionaryId) {
      _bs.set(StringUtils.bytesOf(_dict[dictionaryId]));
      _writer.addStrCol(_colIdx, _bs);
    }
  }

  private static class NumberConverter extends PrimitiveConverter {

    private final int _colIdx;
    private final WriterDelegate _writer;
    private final BufferedString _bs = new BufferedString();

    NumberConverter(int _colIdx, WriterDelegate _writer) {
      this._colIdx = _colIdx;
      this._writer = _writer;
    }

    @Override
    public void addBoolean(boolean value) {
      _writer.addNumCol(_colIdx, value ? 1 : 0);
    }

    @Override
    public void addDouble(double value) {
      _writer.addNumCol(_colIdx, value);
    }

    @Override
    public void addFloat(float value) {
      _writer.addNumCol(_colIdx, value);
    }

    @Override
    public void addInt(int value) {
      _writer.addNumCol(_colIdx, value, 0);
    }

    @Override
    public void addLong(long value) {
      _writer.addNumCol(_colIdx, value, 0);
    }

    @Override
    public void addBinary(Binary value) {
      _bs.set(StringUtils.bytesOf(value.toStringUsingUTF8()));
      _writer.addStrCol(_colIdx, _bs);
    }
  }

  private static class TimestampConverter extends PrimitiveConverter {

    private final int _colIdx;
    private final WriterDelegate _writer;

    TimestampConverter(int _colIdx, WriterDelegate _writer) {
      this._colIdx = _colIdx;
      this._writer = _writer;
    }

    @Override
    public void addLong(long value) {
      _writer.addNumCol(_colIdx, value, 0);
    }
  }

  private static class WriterDelegate {

    private final ParseWriter _writer;
    private final int _numCols;
    private int _col;

    WriterDelegate(ParseWriter writer, int numCols) {
      _writer = writer;
      _numCols = numCols;
      _col = Integer.MIN_VALUE;
    }

    void startLine() {
      _col = -1;
    }

    void endLine() {
      moveToCol(_numCols);
      _writer.newLine();
    }

    int moveToCol(int colIdx) {
      for (int c = _col + 1; c < colIdx; c++) _writer.addInvalidCol(c);
      _col = colIdx;
      return _col;
    }

    void addNumCol(int colIdx, long number, int exp) {
      _writer.addNumCol(moveToCol(colIdx), number, exp);
    }

    void addNumCol(int colIdx, double d) {
      _writer.addNumCol(moveToCol(colIdx), d);
    }

    void addStrCol(int colIdx, BufferedString str) {
      _writer.addStrCol(moveToCol(colIdx), str);
    }

    long lineNum() {
      return _writer.lineNum();
    }

  }

}
