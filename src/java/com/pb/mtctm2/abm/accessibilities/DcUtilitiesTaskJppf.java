package com.pb.mtctm2.abm.accessibilities;

import java.io.File;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import com.pb.common.datafile.TableDataSet;
import com.pb.common.util.Tracer;
import com.pb.mtctm2.abm.ctramp.McLogsumsCalculator;
import com.pb.mtctm2.abm.ctramp.MgraDataManager;
import com.pb.mtctm2.abm.ctramp.ModelStructure;
import com.pb.mtctm2.abm.ctramp.TransitWalkAccessUEC;
import com.pb.common.newmodel.UtilityExpressionCalculator;

import org.apache.log4j.Logger;

public class DcUtilitiesTaskJppf
        implements Callable<List<Object>>
{

    private static final int MIN_EXP_FUNCTION_ARGUMENT = -500;
    
    private MgraDataManager             mgraManager;
    private String[]                    logsumSegments;
    private boolean[]                   hasSizeTerm;
    private double[][]                  expConstants;
    private double[][]                  sizeTerms;

    // store taz-taz exponentiated utilities (period, from taz, to taz)
    private double[][][]                sovExpUtilities;
    private double[][][]                hovExpUtilities;
    private double[][][]                nMotorExpUtilities;


    private float[][]                   accessibilities;

    private int                         startRange;
    private int                         endRange;
    private int                         taskIndex;

    private boolean                     seek;
    private Tracer                      tracer;
    private boolean                     trace;
    private int[]                       traceOtaz;
    private int[]                       traceDtaz;

    private BestTransitPathCalculator bestPathCalculator;
    
    private UtilityExpressionCalculator dcUEC;
    private AccessibilitiesDMU          aDmu;
    
    private HashMap<String, String> rbMap;
   

    public DcUtilitiesTaskJppf( int taskIndex, int startRange, int endRange, MgraDataManager mgraManager,
            double[][][] mySovExpUtilities, double[][][] myHovExpUtilities, double[][][] myNMotorExpUtilities,
            String[] logsumSegments, boolean[] hasSizeTerm, double[][] expConstants,
            double[][] sizeTerms, boolean seek, boolean trace, int[] traceOtaz, int[] traceDtaz,
            String dcUecFileName, int dcDataPage, int dcUtilityPage,
            HashMap<String, String> myRbMap )
    {

        rbMap = myRbMap;
        sovExpUtilities = mySovExpUtilities;
        hovExpUtilities = myHovExpUtilities;
        nMotorExpUtilities = myNMotorExpUtilities;
        
        
        aDmu = new AccessibilitiesDMU();

        File dcUecFile = new File(dcUecFileName);
        dcUEC = new UtilityExpressionCalculator(dcUecFile, dcUtilityPage, dcDataPage, rbMap, aDmu);

        TableDataSet altData = dcUEC.getAlternativeData();
        aDmu.setAlternativeData(altData);
        int alts = dcUEC.getNumberOfAlternatives();

        accessibilities = new float[endRange - startRange + 1][alts];

        this.taskIndex = taskIndex;
        this.startRange = startRange;
        this.endRange = endRange;
        this.mgraManager = mgraManager;
        this.logsumSegments = logsumSegments;
        this.hasSizeTerm = hasSizeTerm;
        this.expConstants = expConstants;
        this.sizeTerms = sizeTerms;
        this.seek = seek;
        this.trace = trace;
        this.traceOtaz = traceOtaz;
        this.traceDtaz = traceDtaz;
    }

    public String getId()
    {
        return Integer.toString(taskIndex);
    }

    public List<Object> call()
    {

        Logger logger = Logger.getLogger(this.getClass());

        String threadName = null;
        try
        {
            threadName = "[" + java.net.InetAddress.getLocalHost().getHostName() + ", task:"
                    + taskIndex + "] " + Thread.currentThread().getName();
        } catch (UnknownHostException e1)
        {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        logger.info(threadName + " - Calculating Accessibilities");

        NonTransitUtilities ntUtilities = new NonTransitUtilities(rbMap, sovExpUtilities, hovExpUtilities, nMotorExpUtilities);
//        ntUtilities.setAllUtilities(ntUtilitiesArrays);
//        ntUtilities.setNonMotorUtilsMap(ntUtilitiesMap);

        McLogsumsCalculator logsumHelper = new McLogsumsCalculator();
        logsumHelper.setupSkimCalculators(rbMap);
        bestPathCalculator = logsumHelper.getBestTransitPathCalculator();

        // set up the tracer object
        tracer = Tracer.getTracer();
        tracer.setTrace(trace);
        if ( trace )
        {
            for (int i = 0; i < traceOtaz.length; i++)
            {
                for (int j = 0; j < traceDtaz.length; j++)
                {
                    tracer.traceZonePair(traceOtaz[i], traceDtaz[j]);
                }
            }
        }

        // get the accessibilities
        int alts = dcUEC.getNumberOfAlternatives();
        TableDataSet altData = dcUEC.getAlternativeData();
        // int logsumCoeffIndex = altData.getColumnPosition("logsumCoeff");
        // int sizeTermCoeffIndex = altData.getColumnPosition("sizeTermCoeff");
        aDmu.setAlternativeData(altData);
        // IndexValues iv = new IndexValues();

        int maxMgra = mgraManager.getMaxMgra();
        int[] mgraNumbers = new int[maxMgra + 1];
        double[] logsums = new double[logsumSegments.length];

        // LOOP OVER ORIGIN MGRA
        int originMgras = 0;
        for (int i = startRange; i <= endRange; i++)
        { // Origin MGRA

            int iMgra = mgraManager.getMgras().get(i);
            ++originMgras;

            mgraNumbers[iMgra] = iMgra;

            // pre-calculate the hov, sov, and non-motorized exponentiated utilities for the origin MGRA.
            // the method called returns cached values if they were already calculated.
            ntUtilities.buildUtilitiesForOrigMgraAndPeriod( iMgra, NonTransitUtilities.PEAK_PERIOD_INDEX );
            ntUtilities.buildUtilitiesForOrigMgraAndPeriod( iMgra, NonTransitUtilities.OFFPEAK_PERIOD_INDEX );
            
            // if(originMgras<=10 || (originMgras % 500) ==0 )
            // logger.info("...Origin MGRA "+iMgra);

            int iTaz = mgraManager.getTaz(iMgra);
            boolean trace = false;
            
            if(tracer.isTraceOn() && tracer.isTraceZone(iTaz)){
            	
             	logger.info("origMGRA, destMGRA, OPSOV, OPHOV, WTRAN, NMOT, SOV0OP, SOV1OP, SOV2OP, HOV0OP, HOV1OP, HOV2OP, HOV0PK, HOV1PK, HOV2PK,  ALL");
             	trace = true;
            }
            //for tracing accessibility and logsum calculations
            String accString = null;
            String lsString = null;

            // LOOP OVER DESTINATION MGRA
            for (Integer jMgra : mgraManager.getMgras())
            { // Destination MGRA

                if (!hasSizeTerm[jMgra]) continue;

                int jTaz = mgraManager.getTaz(jMgra);

                if (seek && !trace) continue;

                double opSovExpUtility = 0;
                double opHovExpUtility = 0;
                try
                {
                    opSovExpUtility = ntUtilities.getSovExpUtility(iTaz, jTaz, NonTransitUtilities.OFFPEAK_PERIOD_INDEX);
                    opHovExpUtility = ntUtilities.getHovExpUtility(iTaz, jTaz, NonTransitUtilities.OFFPEAK_PERIOD_INDEX);
                    //opSovExpUtility = ntUtilities.getAllUtilities()[0][0][iTaz][jTaz];
                    //opHovExpUtility = ntUtilities.getAllUtilities()[1][0][iTaz][jTaz];
                } catch (Exception e)
                {
                    logger.error("exception for op sov/hov utilitiy taskIndex=" + taskIndex
                            + ", i=" + i + ", startRange=" + startRange + ", endRange=" + endRange, e);
                    System.exit(-1);
                }

                // calculate walk-transit exponentiated utility
                // determine the best transit path, which also stores the best utilities array and the best mode
                bestPathCalculator.findBestWalkTransitWalkTaps( TransitWalkAccessUEC.MD, iMgra, jMgra, false, logger);
                
                // sum the exponentiated utilities over modes
                double opWTExpUtility = 0;
                double[] walkTransitWalkUtilities = bestPathCalculator.getBestUtilities();
                for (int k=0; k < walkTransitWalkUtilities.length; k++){
                    if ( walkTransitWalkUtilities[k] > MIN_EXP_FUNCTION_ARGUMENT )
                        opWTExpUtility += Math.exp(walkTransitWalkUtilities[k]);
                }


                double pkSovExpUtility = 0;
                double pkHovExpUtility = 0;
                try
                {
                    pkSovExpUtility = ntUtilities.getSovExpUtility(iTaz, jTaz, NonTransitUtilities.PEAK_PERIOD_INDEX);
                    pkHovExpUtility = ntUtilities.getHovExpUtility(iTaz, jTaz, NonTransitUtilities.PEAK_PERIOD_INDEX);
                    //pkSovExpUtility = ntUtilities.getAllUtilities()[0][1][iTaz][jTaz];
                    //pkHovExpUtility = ntUtilities.getAllUtilities()[1][1][iTaz][jTaz];
                } catch (Exception e)
                {
                    logger.error("exception for pk sov/hov utility taskIndex=" + taskIndex + ", i="
                            + i + ", startRange=" + startRange + ", endRange=" + endRange, e);
                    System.exit(-1);
                }

                // determine the best transit path, which also stores the best utilities array and the best mode
                bestPathCalculator.findBestWalkTransitWalkTaps( TransitWalkAccessUEC.AM, iMgra, jMgra, false, logger);
                
                // sum the exponentiated utilities over modes
                double pkWTExpUtility = 0;
                walkTransitWalkUtilities = bestPathCalculator.getBestUtilities();
                for (int k=0; k < walkTransitWalkUtilities.length; k++){
                    if ( walkTransitWalkUtilities[k] > MIN_EXP_FUNCTION_ARGUMENT )
                        pkWTExpUtility += Math.exp(walkTransitWalkUtilities[k]);
                }

                double nmExpUtility = 0;
                try
                {
                    nmExpUtility = ntUtilities.getNMotorExpUtility(iMgra, jMgra, NonTransitUtilities.OFFPEAK_PERIOD_INDEX);
                } catch (Exception e)
                {
                    logger.error("exception for non-motorized utilitiy taskIndex=" + taskIndex
                            + ", i=" + i + ", startRange=" + startRange + ", endRange=" + endRange, e);
                    System.exit(-1);
                }

                Arrays.fill(logsums, -999f);

                // 0: OP SOV
                logsums[0] = Math.log(opSovExpUtility);

                // 1: OP HOV
                logsums[1] = Math.log(opHovExpUtility);

                // 2: Walk-Transit
                if (opWTExpUtility > 0) logsums[2] = Math.log(opWTExpUtility);

                // 3: Non-Motorized
                if (nmExpUtility > 0) logsums[3] = Math.log(nmExpUtility);

                // 4: SOVLS_0
                logsums[4] = Math.log(opSovExpUtility * expConstants[0][0] + opWTExpUtility
                        * expConstants[0][2] + nmExpUtility * expConstants[0][3]);
                // 5: SOVLS_1
                logsums[5] = Math.log(opSovExpUtility * expConstants[1][0] + opWTExpUtility
                        * expConstants[1][2] + nmExpUtility * expConstants[1][3]);

                // 6: SOVLS_2
                logsums[6] = Math.log(opSovExpUtility * expConstants[2][0] + opWTExpUtility
                        * expConstants[2][2] + nmExpUtility * expConstants[2][3]);

                // 7: HOVLS_0_OP
                logsums[7] = Math.log(opHovExpUtility * expConstants[0][1] + opWTExpUtility
                        * expConstants[0][2] + nmExpUtility * expConstants[0][3]);

                // 8: HOVLS_1_OP
                logsums[8] = Math.log(opHovExpUtility * expConstants[1][1] + opWTExpUtility
                        * expConstants[1][2] + nmExpUtility * expConstants[1][3]);

                // 9: HOVLS_2_OP
                logsums[9] = Math.log(opHovExpUtility * expConstants[2][1] + opWTExpUtility
                        * expConstants[2][2] + nmExpUtility * expConstants[2][3]);

                // 10: HOVLS_0_PK
                logsums[10] = Math.log(pkHovExpUtility * expConstants[0][1] + pkWTExpUtility
                        * expConstants[0][2] + nmExpUtility * expConstants[0][3]);

                // 11: HOVLS_1_PK
                logsums[11] = Math.log(pkHovExpUtility * expConstants[1][1] + pkWTExpUtility
                        * expConstants[1][2] + nmExpUtility * expConstants[1][3]);

                // 12: HOVLS_2_PK
                logsums[12] = Math.log(pkHovExpUtility * expConstants[2][1] + pkWTExpUtility
                        * expConstants[2][2] + nmExpUtility * expConstants[2][3]);

                // 13: ALL
                logsums[13] = Math.log(pkSovExpUtility * expConstants[3][0] + pkHovExpUtility
                        * expConstants[3][1] + pkWTExpUtility * expConstants[3][2] + nmExpUtility
                        * expConstants[3][3]);

                aDmu.setLogsums(logsums);
                aDmu.setSizeTerms(sizeTerms[jMgra]);
                // double[] utilities = dcUEC.solve(iv, aDmu, null);

                if (trace)
                {
                    String printString = new String();
                    printString += (iMgra + "," + jMgra);
                    for(int j =0;j<14;++j){
                    	printString += ","+String.format("%9.2f", logsums[j]);
                    }
                    logger.info(printString);	
                    
                    accString = new String();
                    accString = "iMgra, jMgra, Alternative, Logsum, SizeTerm, Accessibility\n";
                    
                }
                // add accessibilities for origin mgra
                for (int alt = 0; alt < alts; ++alt)
                {

                    double logsum = aDmu.getLogsum(alt + 1);
                    double sizeTerm = aDmu.getSizeTerm(alt + 1);

                    accessibilities[originMgras - 1][alt] += (Math.exp(logsum) * sizeTerm);

                    if (trace)
                    {
                        accString += iMgra +"," + alt + "," + logsum + ","
                                + sizeTerm + "," + accessibilities[originMgras - 1][alt] + "\n";
                    }
                }
            } //end for destinations
            
            if(trace)
            {
            	logger.info(accString);
            }


            // calculate the logsum
            for (int alt = 0; alt < alts; ++alt){
                if (accessibilities[originMgras - 1][alt] > 0)
                    accessibilities[originMgras - 1][alt] = (float) Math
                            .log(accessibilities[originMgras - 1][alt]);
            	
            }
            
        }

        List<Object> resultBundle = new ArrayList<Object>(4);
        resultBundle.add(taskIndex);
        resultBundle.add(startRange);
        resultBundle.add(endRange);
        resultBundle.add(accessibilities);

        return resultBundle;

    }

}
