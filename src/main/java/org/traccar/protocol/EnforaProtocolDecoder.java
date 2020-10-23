/*
 * Copyright 2012 - 2018 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.Protocol;
import org.traccar.helper.BufferUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.traccar.helper.BitUtil;

public class EnforaProtocolDecoder extends BaseProtocolDecoder {

    public EnforaProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .number("\\s*(d+)")                  // event
            .number("\\s*(d{15})")               // imei
            .number("\\s*(d*)")                  // in outs
            .number("\\s*(-?d*)")                // AD1 (divide by 1000)
            .number("\\s*(-?d*)")                // AD2 (divide by 1000)
            .expression(" \\$?GPRMC,")
            .number("(dd)(dd)(dd).?d*,")         // time (hhmmss)
            .expression("([AV]),")               // validity
            .number("(dd)(dd.d+),")              // latitude
            .expression("([NS]),")
            .number("(ddd)(dd.d+),")             // longitude
            .expression("([EW]),")
            .number("(d+.d+)?,")                 // speed
            .number("(d+.d+)?,")                 // course
            .number("(dd)(dd)(dd),")             // date (ddmmyy)
            .number("(-?d*),").optional()        // altitude
            .number("(-?d*),").optional()        // satelites
            .expression("[^*]*\\*\\d+")          // checksum
            .number("\\s*(d{2})").optional()     // battery percent
            .number("\\s*(d*)")                  // virtual odometer
            .number("\\s*(d*)")                  // gps odometer
            .number("\\s*(d{5})").optional()     // battery voltage (divide by 1000)
            .any()
            .compile();

    public static final int IMEI_LENGTH = 15;

    private String decodeAlarm(int value) {
        switch (value) {
            case 12:
                return Position.ALARM_SOS;
            case 14:
                return Position.ALARM_POWER_CUT;
            case 15:
                return Position.ALARM_POWER_RESTORED;
            case 17:
                return Position.ALARM_POWER_OFF;
            case 18:
                return Position.ALARM_POWER_ON;
            case 19:
                return Position.ALARM_GPS_ANTENNA_CUT;
            case 40:
                return Position.ALARM_OVERSPEED;
            case 91:
                return Position.ALARM_BRAKING;
            case 92:
                return Position.ALARM_ACCELERATION;
            default:
                return null;
        }
    }

    private Position decodeResult(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress);
        if (deviceSession == null) {
            return null;
        }
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        position.setFixTime(position.getDeviceTime());

        buf.skipBytes(9);
        String result = buf.toString(buf.readerIndex(), buf.readableBytes() - 2, StandardCharsets.US_ASCII);
        position.set(Position.KEY_RESULT, result);

        return position;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        if (BufferUtil.indexOf("OK", buf) != -1 || BufferUtil.indexOf("ERROR", buf) != -1) {
            return decodeResult(channel, remoteAddress, buf);
        }

        String sentence = buf.toString(7, buf.readableBytes() - 7, StandardCharsets.US_ASCII);
        Parser parser = new Parser(PATTERN, sentence);

        if (!parser.matches()) {
            return null;
        }

        int event = parser.nextInt(0);

        String imei = parser.next();
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (event == 10 || event == 11) {
            position.set(Position.KEY_IGNITION, event == 10);
        } else if (event > 0) {
            position.set(Position.KEY_ALARM, decodeAlarm(event));
        }

        if (parser.hasNext()) {
            int status = parser.nextInt();
            for (int i = 1; i <= 9; i++) {
                position.set(Position.PREFIX_IO + i, BitUtil.check(status, i - 1));
            }
        }

        if (parser.hasNext()) {
            position.set(Position.PREFIX_ADC + 1, parser.nextInt() * 0.001);
        }

        if (parser.hasNext()) {
            position.set(Position.PREFIX_ADC + 2, parser.nextInt()  * 0.001);
        }

        DateBuilder dateBuilder = new DateBuilder()
                .setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));

        position.setValid(parser.next().equals("A"));
        position.setLatitude(parser.nextCoordinate());
        position.setLongitude(parser.nextCoordinate());
        position.setSpeed(parser.nextDouble(0));
        position.setCourse(parser.nextDouble(0));

        dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        position.setTime(dateBuilder.getDate());

        if (parser.hasNext()) {
            position.setAltitude(parser.nextDouble());
        }

        if (parser.hasNext()) {
            position.set(Position.KEY_SATELLITES, parser.nextInt());
        }

        if (parser.hasNext()) {
            position.set(Position.KEY_BATTERY_LEVEL, parser.nextInt());
        }

        if (parser.hasNext()) {
            position.set(Position.KEY_ODOMETER_TRIP, parser.nextInt());
        }

        if (parser.hasNext()) {
            position.set(Position.KEY_ODOMETER, parser.nextInt());
        }

        if (parser.hasNext()) {
            position.set(Position.KEY_POWER, parser.nextInt() * 0.001);
        }

        return position;
    }

}
