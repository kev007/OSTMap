package org.iidp.ostmap.commons.accumulo.geoTemp;

import com.github.davidmoten.geo.Base32;
import com.github.davidmoten.geo.Coverage;
import com.github.davidmoten.geo.GeoHash;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.hadoop.io.Text;
import org.iidp.ostmap.commons.accumulo.FlinkEnvManager;
import org.iidp.ostmap.commons.enums.AccumuloIdentifiers;
import org.iidp.ostmap.commons.enums.TableIdentifier;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * provides easy search access for GeoTemporalIndex table
 */
public class GeoTemporalTweetQuery {

    private Double north, east, south, west;
    private Long startTime, endTime;
    private Short startDay, endDay;
    private TweetCallback tc;
    private FlinkEnvManager fem;

    public GeoTemporalTweetQuery(String configPath) throws IOException {

        fem = new FlinkEnvManager(configPath);

    }

    public GeoTemporalTweetQuery(){

    }

    public void setConfig(String configPath)throws IOException {

        fem = new FlinkEnvManager(configPath);

    }



    public void setBoundingBox(double north,
                               double east,
                               double south,
                               double west){

        this.north = north;
        this.east = east;
        this.west = west;
        this.south = south;
    }

    public void setTimeRange(long startTime, long endTime){

        this.startTime = startTime;
        this.endTime = endTime;

        LocalDate epoch = LocalDate.ofEpochDay(0);

        LocalDate startDate = (new Date(startTime)).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        startDay = (short) ChronoUnit.DAYS.between(epoch, startDate);
        LocalDate endDate = (new Date(startTime)).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        endDay = (short) ChronoUnit.DAYS.between(epoch, endDate);
    }

    public void setCallback(TweetCallback tc){

        this.tc = tc;
    }

    public void query() throws AccumuloSecurityException, AccumuloException, TableNotFoundException {

        if(north == null ||
                east == null ||
                west == null ||
                south == null ||
                startTime == null ||
                endTime == null ||
                tc == null ||
                fem == null){

            System.err.println("Error: not all query parameters set");
            return;
        }


        Connector conn = fem.getConnector();
        Authorizations auths = new Authorizations("standard");
        BatchScanner geoTempScan = conn.createBatchScanner(TableIdentifier.GEO_TEMPORAL_INDEX.get(), auths,32);
        BatchScanner rawTwitterScan = conn.createBatchScanner(TableIdentifier.RAW_TWITTER_DATA.get(),auths,32);


        List<Range> geoRangeList = getRangeList();

        geoTempScan.setRanges(geoRangeList);
        //TODO: set filter

        List<Range> rawRangeList = new ArrayList<>();

        for (Map.Entry<Key, Value> entry : geoTempScan) {

            rawRangeList.add(new Range(entry.getKey().getColumnFamily()));
        }

        geoTempScan.close();


        rawTwitterScan.setRanges(rawRangeList);
        //TODO: set filter
        for(Map.Entry<Key, Value> entry : rawTwitterScan){
            //get RawTwitterData from keys

            tc.process(entry.getValue().toString());

        }
        rawTwitterScan.close();
    }

    /**
     * checks if a GeoTimeIndex key matches the exact window of the query
     * @param k
     * @return
     */
    /*private boolean isInExactWindow(Key k){

        Long tweetTime = ByteBuffer.wrap(k.getColumnFamily().getBytes()).getLong();

        ByteBuffer bb = ByteBuffer.wrap(k.getColumnQualifier().getBytes());

        float tweetLat = bb.getFloat();
        float tweetLon = bb.getFloat();

        return startTime < tweetTime &&
                tweetTime < endTime &&
                west < tweetLon &&
                tweetLon < east &&
                south < tweetLat &&
                tweetLat < north;
    }*/


    /**
     *
     * @return ranges for rowkeys [0-255][startDay-endDay][setOfGeohashes]
     */
    private List<Range> getRangeList(){
        List<Range> rangeList = new ArrayList<>();

        Coverage coverage = GeoHash.coverBoundingBox(west,north,east,south,8);

        Set<String> hashes = coverage.getHashes();

        for(String hash: hashes){
            for(short day = startDay; day < endDay; day++){
                for(byte spreadingByte = (byte) 0; spreadingByte < 255; spreadingByte++){

                    ByteBuffer startKey = ByteBuffer.allocate(11);
                    startKey.put(spreadingByte).putShort(day).put(hash.getBytes());

                    rangeList.add(new Range(new Text(startKey.array())));
                }
            }
        }

        return rangeList;
    }

    /**
     * creates a list of successive subsets of hashes (Tuple2<startHash, endHash>) from a set of hashes
     * @param hashStrings
     * @return
     */
   /* protected List<Tuple2<String,String>> mergeHashes(Set<String> hashStrings){

        List<Tuple2<String,String>> mergedHashes = new ArrayList<>();

        List<String> sortedHashes = new ArrayList<>(hashStrings);
        Collections.sort(sortedHashes);

        int i = 0;
        boolean isSuccessive;
        while(i < sortedHashes.size()){

            Tuple2<String,String > nextTupel = new Tuple2<>();
            nextTupel.f0 = sortedHashes.get(i);
            nextTupel.f1 = sortedHashes.get(i);
            do{
                isSuccessive = false;
                if(i+1 < sortedHashes.size() && isNext(sortedHashes.get(i),sortedHashes.get(i+1))){

                    i++;
                    nextTupel.f1 = sortedHashes.get(i);
                    isSuccessive = true;
                }
            }while(isSuccessive);

            mergedHashes.add(nextTupel);
            i++;
        }

        return mergedHashes;
    }*/

    /**
     * checks if two hashes are successive
     * @param hash1
     * @param hash2
     * @return
     */
  /*  protected Boolean isNext(String hash1, String hash2){

        return hash2.equals(getNextHash(hash1));
    }

    //TODO: remove
    /**
     * calculates the next hash
     * @param hash
     * @return
     */
  /*  protected static String getNextHash(String hash) {
        long decode = Base32.decodeBase32(hash);
        decode++;
        return Base32.encodeBase32(decode,8);
    }*/
}
