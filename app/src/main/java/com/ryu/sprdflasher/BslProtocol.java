package com.ryu.sprdflasher;

public class BslProtocol {
    public static final byte HDLC_HEADER = 0x7e;
    public static final byte HDLC_ESCAPE = 0x7d;

    public static final int CMD_CONNECT = 0x00;
    public static final int CMD_START_DATA = 0x01;
    public static final int CMD_MIDST_DATA = 0x02;
    public static final int CMD_END_DATA = 0x03;
    public static final int CMD_EXEC_DATA = 0x04;
    public static final int CMD_NORMAL_RESET = 0x05;
    public static final int CMD_READ_FLASH = 0x06;
    public static final int CMD_ERASE_FLASH = 0x0A;
    public static final int CMD_REPARTITION = 0x0B;
    public static final int CMD_READ_MEM = 0x07;
    public static final int CMD_READ_START = 0x10;
    public static final int CMD_READ_MIDST = 0x11;
    public static final int CMD_READ_END = 0x12;
    public static final int CMD_KEEP_CHARGE = 0x13;
    public static final int CMD_DISABLE_TRANSCODE = 0x21;
    public static final int CMD_READ_PARTITION = 0x2D;
    public static final int CMD_CHECK_BAUD = 0x7E;
    public static final int CMD_POWER_OFF = 0x17;
    public static final int CMD_READ_CHIP_UID = 0x1A;

    public static final int REP_ACK = 0x80;
    public static final int REP_VER = 0x81;
    public static final int REP_INVALID_CMD = 0x82;
    public static final int REP_OPERATION_FAILED = 0x84;
    public static final int REP_READ_FLASH = 0x93;
    public static final int REP_READ_MEM = 0x94;
    public static final int REP_INCOMPATIBLE_PARTITION = 0x96;
    public static final int REP_READ_PARTITION = 0xBA;
    public static final int REP_READ_CHIP_UID = 0xAB;
    public static final int REP_LOG = 0xFF;

    private boolean useCrc16 = false;
    private boolean useTranscode = true;

    public void setUseCrc16(boolean use) { this.useCrc16 = use; }
    public void setUseTranscode(boolean use) { this.useTranscode = use; }

    public static int crc16(int crc, byte[] data, int offset, int len) {
        crc &= 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[offset + i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc << 1) ^ (((0 - (crc >>> 15)) & 0x11021));
            }
        }
        return crc & 0xFFFF;
    }

    public static int checksum(byte[] data, int offset, int len) {
        long crc = 0;
        int i = 0;
        while (len > 1) {
            crc += ((data[offset + i + 1] & 0xFF) << 8) | (data[offset + i] & 0xFF);
            i += 2;
            len -= 2;
        }
        if (len > 0) crc += (data[offset + i] & 0xFF);
        crc = (crc >> 16) + (crc & 0xFFFF);
        crc += crc >> 16;
        return (int)(~crc & 0xFFFF);
    }

    public static byte[] transcode(byte[] src, int offset, int len) {
        int count = 0;
        for (int i = 0; i < len; i++) {
            byte b = src[offset + i];
            if (b == HDLC_HEADER || b == HDLC_ESCAPE) count++;
        }
        byte[] dst = new byte[len + count];
        int j = 0;
        for (int i = 0; i < len; i++) {
            byte b = src[offset + i];
            if (b == HDLC_HEADER || b == HDLC_ESCAPE) {
                dst[j++] = HDLC_ESCAPE;
                dst[j++] = (byte)(b ^ 0x20);
            } else {
                dst[j++] = b;
            }
        }
        return dst;
    }

    public static byte[] detranscode(byte[] src, int offset, int len) {
        java.util.ArrayList<Byte> list = new java.util.ArrayList<>();
        boolean escape = false;
        for (int i = 0; i < len; i++) {
            byte b = src[offset + i];
            if (escape) {
                list.add((byte)(b ^ 0x20));
                escape = false;
            } else if (b == HDLC_ESCAPE) {
                escape = true;
            } else {
                list.add(b);
            }
        }
        byte[] dst = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) dst[i] = list.get(i);
        return dst;
    }

    public byte[] encodeMessage(int type, byte[] data, int dataLen) {
        if (type == CMD_CHECK_BAUD) {
            byte[] buf = new byte[dataLen];
            java.util.Arrays.fill(buf, HDLC_HEADER);
            return buf;
        }
        if (dataLen > 0xFFFF) return null;

        int rawLen = 4 + dataLen + 2;
        byte[] raw = new byte[rawLen];
        raw[0] = (byte)((type >> 8) & 0xFF);
        raw[1] = (byte)(type & 0xFF);
        raw[2] = (byte)((dataLen >> 8) & 0xFF);
        raw[3] = (byte)(dataLen & 0xFF);
        if (dataLen > 0 && data != null) {
            System.arraycopy(data, 0, raw, 4, dataLen);
        }

        int chk = useCrc16 ? crc16(0, raw, 0, rawLen - 2) : checksum(raw, 0, rawLen - 2);
        raw[rawLen - 2] = (byte)((chk >> 8) & 0xFF);
        raw[rawLen - 1] = (byte)(chk & 0xFF);

        if (useTranscode) {
            byte[] enc = transcode(raw, 0, rawLen);
            byte[] finalBuf = new byte[enc.length + 2];
            finalBuf[0] = HDLC_HEADER;
            System.arraycopy(enc, 0, finalBuf, 1, enc.length);
            finalBuf[finalBuf.length - 1] = HDLC_HEADER;
            return finalBuf;
        } else {
            byte[] finalBuf = new byte[rawLen + 2];
            finalBuf[0] = HDLC_HEADER;
            System.arraycopy(raw, 0, finalBuf, 1, rawLen);
            finalBuf[finalBuf.length - 1] = HDLC_HEADER;
            return finalBuf;
        }
    }

    public static class ParsedMsg {
        public int type;
        public int len;
        public byte[] data;
        public boolean valid;
    }

    public static ParsedMsg parseMessage(byte[] buf, int offset, int totalLen) {
        ParsedMsg msg = new ParsedMsg();
        if (totalLen < 6) { msg.valid = false; return msg; }
        int start = -1, end = -1;
        for (int i = offset; i < offset + totalLen; i++) {
            if (buf[i] == HDLC_HEADER) {
                if (start == -1) start = i;
                else if (i > start + 1) { end = i; break; }
            }
        }
        if (start == -1 || end == -1 || end - start < 7) {
            msg.valid = false; return msg;
        }
        byte[] raw = detranscode(buf, start + 1, end - start - 1);
        if (raw.length < 6) { msg.valid = false; return msg; }
        msg.type = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        msg.len = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
        if (raw.length != msg.len + 6) { msg.valid = false; return msg; }

        int chk = ((raw[raw.length - 2] & 0xFF) << 8) | (raw[raw.length - 1] & 0xFF);
        int calcChk = checksum(raw, 0, raw.length - 2);
        if (chk != calcChk) calcChk = crc16(0, raw, 0, raw.length - 2);
        if (chk != calcChk) { msg.valid = false; return msg; }

        msg.data = new byte[msg.len];
        if (msg.len > 0) System.arraycopy(raw, 4, msg.data, 0, msg.len);
        msg.valid = true;
        return msg;
    }

    public static byte[] u32be(long val) {
        return new byte[] {
            (byte)((val >> 24) & 0xFF), (byte)((val >> 16) & 0xFF),
            (byte)((val >> 8) & 0xFF), (byte)(val & 0xFF)
        };
    }

    public static byte[] u32le(long val) {
        return new byte[] {
            (byte)(val & 0xFF), (byte)((val >> 8) & 0xFF),
            (byte)((val >> 16) & 0xFF), (byte)((val >> 24) & 0xFF)
        };
    }

    public static long readU32be(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off+1] & 0xFFL) << 16) |
               ((b[off+2] & 0xFFL) << 8) | (b[off+3] & 0xFFL);
    }

    public static long readU32le(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off+1] & 0xFFL) << 8) |
               ((b[off+2] & 0xFFL) << 16) | ((b[off+3] & 0xFFL) << 24);
    }

    public static byte[] wstr(String s) {
        byte[] b = new byte[72];
        for (int i = 0; i < s.length() && i < 36; i++) {
            b[i*2] = (byte)(s.charAt(i) & 0xFF);
            b[i*2+1] = 0;
        }
        return b;
    }
}
