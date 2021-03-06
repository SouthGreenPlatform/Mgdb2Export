/*******************************************************************************
 * MGDB Export - Mongo Genotype DataBase, export handlers
 * Copyright (C) 2016 - 2019, <CIRAD> <IRD>
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License, version 3 as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * See <http://www.gnu.org/licenses/agpl.html> for details about GNU General
 * Public License V3.
 *******************************************************************************/
package fr.cirad.mgdb.exporting.markeroriented;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.MongoCollection;

import fr.cirad.mgdb.exporting.AbstractExportWritingThread;
import fr.cirad.mgdb.exporting.IExportHandler;
import fr.cirad.mgdb.exporting.tools.ExportManager;
import fr.cirad.mgdb.model.mongo.maintypes.GenotypingSample;
import fr.cirad.mgdb.model.mongo.maintypes.Individual;
import fr.cirad.mgdb.model.mongo.maintypes.VariantData;
import fr.cirad.mgdb.model.mongo.maintypes.VariantRunData;
import fr.cirad.mgdb.model.mongo.subtypes.ReferencePosition;
import fr.cirad.mgdb.model.mongo.subtypes.SampleGenotype;
import fr.cirad.mgdb.model.mongodao.MgdbDao;
import fr.cirad.tools.AlphaNumericComparator;
import fr.cirad.tools.Helper;
import fr.cirad.tools.ProgressIndicator;
import fr.cirad.tools.mongo.MongoTemplateManager;
import htsjdk.variant.variantcontext.VariantContext.Type;

/**
 * The Class EigenstratExportHandler.
 */
public class EigenstratExportHandler extends AbstractMarkerOrientedExportHandler {

    /**
     * The Constant LOG.
     */
    private static final Logger LOG = Logger.getLogger(EigenstratExportHandler.class);

    /**
     * The Constant EIGENSTRAT_FORMAT.
     */
    public static final String EIGENSTRAT_FORMAT = "EIGENSTRAT";
    
	public static final byte missingData = 9;

    /**
     * The supported variant types.
     */
    private static List<String> supportedVariantTypes;

    static {
        supportedVariantTypes = new ArrayList<String>();
        supportedVariantTypes.add(Type.SNP.toString());
    }

    /**
     * Gets the individual gender code.
     *
     * @param sModule the module
     * @param individual the individual
     * @return the individual gender code
     */
    protected String getIndividualGenderCode(String sModule, String individual) {
        return "U";
    }

    /**
     * Gets the populations from samples.
     *
     * @param sModule the module
     * @param samples the samples
     * @return the populations from samples
     */
    @SuppressWarnings("unchecked")
    protected List<String> getPopulationsFromSamples(final String sModule, final List<GenotypingSample> samples) {
        ArrayList<String> result = new ArrayList<String>();
        for (Individual individual : MgdbDao.getIndividualsFromSamples(sModule, samples)) {
            result.add(individual.getPopulation());
        }
        return result;
    }

    /* (non-Javadoc)
	 * @see fr.cirad.mgdb.exporting.IExportHandler#getExportFormatName()
     */
    @Override
    public String getExportFormatName() {
        return EIGENSTRAT_FORMAT;
    }

    /* (non-Javadoc)
	 * @see fr.cirad.mgdb.exporting.IExportHandler#getExportFormatDescription()
     */
    @Override
    public String getExportFormatDescription() {
        return "Exports zipped ind, snp and eigenstratgeno files, along with an optional remark-file. See <a target='_blank' href='https://github.com/argriffing/eigensoft/blob/master/CONVERTF/README'>https://github.com/argriffing/eigensoft/blob/master/CONVERTF/README</a> for more details";
    }

    /* (non-Javadoc)
	 * @see fr.cirad.mgdb.exporting.markeroriented.AbstractMarkerOrientedExportHandler#getSupportedVariantTypes()
     */
    @Override
    public List<String> getSupportedVariantTypes() {
        return supportedVariantTypes;
    }

	@Override
	public String getExportArchiveExtension() {
		return "zip";
	}

    /* (non-Javadoc)
	 * @see fr.cirad.mgdb.exporting.markeroriented.AbstractMarkerOrientedExportHandler#exportData(java.io.OutputStream, java.lang.String, java.util.List, fr.cirad.tools.ProgressIndicator, com.mongodb.DBCursor, java.util.Map, int, int, java.util.Map)
     */
    @Override
	public void exportData(OutputStream outputStream, String sModule, Collection<String> individuals1, Collection<String> individuals2, ProgressIndicator progress, String tmpVarCollName, Document varQuery, long markerCount, Map<String, String> markerSynonyms, HashMap<String, Float> annotationFieldThresholds, HashMap<String, Float> annotationFieldThresholds2, List<GenotypingSample> samplesToExport, Map<String, InputStream> readyToExportFiles) throws Exception {
//
//    public void exportData(OutputStream outputStream, String sModule, Collection<GenotypingSample> samples1, Collection<GenotypingSample> samples2, ProgressIndicator progress, MongoCollection<Document> varColl, Document varQuery, Map<String, String> markerSynonyms, HashMap<String, Float> annotationFieldThresholds, HashMap<String, Float> annotationFieldThresholds2, List<GenotypingSample> samplesToExport, Map<String, InputStream> readyToExportFiles) throws Exception {
        File warningFile = File.createTempFile("export_warnings_", "");
        FileWriter warningFileWriter = new FileWriter(warningFile);
        File snpFile = null;

        try {
            snpFile = File.createTempFile("snpFile", "");
            FileWriter snpFileWriter = new FileWriter(snpFile);

            MongoTemplate mongoTemplate = MongoTemplateManager.get(sModule);
            ZipOutputStream zos = IExportHandler.createArchiveOutputStream(outputStream, readyToExportFiles);
    		MongoCollection collWithPojoCodec = mongoTemplate.getDb().withCodecRegistry(ExportManager.pojoCodecRegistry).getCollection(tmpVarCollName != null ? tmpVarCollName : mongoTemplate.getCollectionName(VariantRunData.class));

    		List<String> sortedIndividuals = samplesToExport.stream().map(gs -> gs.getIndividual()).distinct().sorted(new AlphaNumericComparator<String>()).collect(Collectors.toList());
 
            String exportName = sModule + "__" + markerCount + "variants__" + sortedIndividuals.size() + "individuals";
            
            zos.putNextEntry(new ZipEntry(exportName + ".eigenstratgeno"));

			final Map<Integer, String> sampleIdToIndividualMap = new HashMap<>();
			for (GenotypingSample gs : samplesToExport)
				sampleIdToIndividualMap.put(gs.getId(), gs.getIndividual());

            ArrayList<Comparable> unassignedMarkers = new ArrayList<>();

    		int nQueryChunkSize = IExportHandler.computeQueryChunkSize(mongoTemplate, markerCount);
    		
//    		long b4 = System.currentTimeMillis();
    		AbstractExportWritingThread writingThread = new AbstractExportWritingThread() {
    			public void run() {
//    				long b4 = System.currentTimeMillis();
//    				String id = markerRunsToWrite.keySet().iterator().next();
//    				System.out.println("writing " + id);
    				
    				for (String idOfVarToWrite : markerRunsToWrite.keySet()) {
    					if (progress.isAborted() || progress.getError() != null)
    						return;

    					List<VariantRunData> runsToWrite = markerRunsToWrite.get(idOfVarToWrite);
    					if (runsToWrite.isEmpty())
    						continue;

    					StringBuffer sb = new StringBuffer();
    					try
    					{
//    						long b4 = System.currentTimeMillis();
    						
    		                if (markerSynonyms != null) {
    		                	String syn = markerSynonyms.get(idOfVarToWrite);
    		                    if (syn != null)
    		                    	idOfVarToWrite = syn;
    		                }

    		                VariantRunData vrd = runsToWrite.get(0);
//    		                boolean fIsSNP = vrd.getType().equals(Type.SNP.toString());

    		                ReferencePosition rp = vrd.getReferencePosition();
    	                    snpFileWriter.write(idOfVarToWrite + "\t" + (rp == null ? 0 : rp.getSequence()) + "\t" + 0 + "\t" + (rp == null ? 0 : rp.getStartSite()) + LINE_SEPARATOR);    		                
//    		                sb.append(/*(variantId == null ? variant.getId() : */idOfVarToWrite/*)*/ + "\t" + StringUtils.join((vrd).getKnownAlleleList(), "/") + "\t" + (rp == null ? 0 : rp.getSequence()) + "\t" + (rp == null ? 0 : rp.getStartSite()) + "\t" + "+\tNA\tNA\tNA\tNA\tNA\tNA");
    	
    		                Map<String, LinkedHashSet<String>> individualGenotypes = new TreeMap<String, LinkedHashSet<String>>(new AlphaNumericComparator<String>());

    	                	for (VariantRunData run : runsToWrite) {
    	                    	for (Integer sampleId : run.getSampleGenotypes().keySet()) {
    								SampleGenotype sampleGenotype = run.getSampleGenotypes().get(sampleId);
    	                            String gtCode = sampleGenotype.getCode();
    	                            String individualId = sampleIdToIndividualMap.get(sampleId);
    	                            
    								if (gtCode == null || !VariantData.gtPassesVcfAnnotationFilters(individualId, sampleGenotype, individuals1, annotationFieldThresholds, individuals2, annotationFieldThresholds2))
    									continue;	// skip genotype
    								
    	                            LinkedHashSet<String> storedIndividualGenotypes = individualGenotypes.get(individualId);
    	                            if (storedIndividualGenotypes == null) {
    	                                storedIndividualGenotypes = new LinkedHashSet<String>();
    	                                individualGenotypes.put(individualId, storedIndividualGenotypes);
    	                            }
    	                            storedIndividualGenotypes.add(gtCode);
    	                        }
    	                    }

    	                    boolean fFirstLoopExecution = true;
    	                    if (individualGenotypes.isEmpty())
    	                    {
    	                    	for (int i=0; i<sortedIndividuals.size(); i++)
    	                    		sb.append(missingData);
    	                    	warningFileWriter.write("- No genotypes found for variant " + idOfVarToWrite + "\n");
    	                    }
    	                    else
    		                    for (String individualId : sortedIndividuals /* we use this object because it has the proper ordering*/) {
    		                        LinkedHashSet<String> genotypes = individualGenotypes.get(individualId);
    		                        HashMap<Object, Integer> genotypeCounts = new HashMap<Object, Integer>(); // will help us to keep track of missing genotypes
    		                        int highestGenotypeCount = 0;
    		                        String mostFrequentGenotype = null;
    		                        if (genotypes != null) {
    		                            for (String genotype : genotypes) {
    		                                if (genotype == null) {
    		                                    continue; /* skip missing genotypes */
    		                                }
    		
    		                                int gtCount = 1 + Helper.getCountForKey(genotypeCounts, genotype);
    		                                if (gtCount > highestGenotypeCount) {
    		                                    highestGenotypeCount = gtCount;
    		                                    mostFrequentGenotype = genotype;
    		                                }
    		                                genotypeCounts.put(genotype, gtCount);
    		                            }
    		                        }
    		
    		                        List<String> alleles = vrd.safelyGetAllelesFromGenotypeCode(mostFrequentGenotype, mongoTemplate);
    		                        int nOutputCode = 0;
    		                        if (mostFrequentGenotype == null)
    		                            nOutputCode = 9;
    		                        else
    		                            for (String all : Helper.split(mostFrequentGenotype, "/"))
    		                                if ("0".equals(all))
    		                                    nOutputCode++;
    		
    		                        if (fFirstLoopExecution && vrd.getKnownAlleleList().size() > 2) {
    		                            warningFileWriter.write("- Variant " + vrd.getId() + " is multi-allelic. Make sure Eigenstrat genotype encoding specifications are suitable for you.\n");
    		                        }
    		                        sb.append(nOutputCode);
    		
    		                        if (genotypeCounts.size() > 1 || alleles.size() > 2) {
    		                            if (genotypeCounts.size() > 1) {
    		                                warningFileWriter.write("- Dissimilar genotypes found for variant " + idOfVarToWrite + ", individual " + individualId + ". Exporting most frequent: " + nOutputCode + "\n");
    		                            }
    		                            if (alleles.size() > 2) {
    		                                warningFileWriter.write("- More than 2 alleles found for variant " + idOfVarToWrite + ", individual " + individualId + ". Exporting only the first 2 alleles.\n");
    		                            }
    		                        }
    		                        fFirstLoopExecution = false;
    		                    }
    		                    sb.append(LINE_SEPARATOR);
   		        				zos.write(sb.toString().getBytes());
    	                }
    					catch (Exception e)
    					{
    						if (progress.getError() == null)	// only log this once
    							LOG.debug("Unable to export " + idOfVarToWrite, e);
    						progress.setError("Unable to export " + idOfVarToWrite + ": " + e.getMessage());
    					}
    				}
    				markerRunsToWrite.clear();
//    				long duration = System.currentTimeMillis() - b4;
//    				System.out.println("wrote " + id + " in " + duration + "ms");
    			}
    		};

    		ExportManager exportManager = new ExportManager(mongoTemplate, collWithPojoCodec, VariantRunData.class, varQuery, samplesToExport, true, nQueryChunkSize, writingThread, markerCount, warningFileWriter, progress);
    		exportManager.readAndWrite();
            zos.closeEntry();            
            
            if (unassignedMarkers.size() > 0)
            	LOG.info("No chromosomal position found for " + unassignedMarkers.size() + " markers " + StringUtils.join(unassignedMarkers, ", "));
            
            StringBuffer indFileContents = new StringBuffer();
            for (String individual : sortedIndividuals)
            {
            	String pop = MgdbDao.getIndividualPopulation(sModule, individual);
                indFileContents.append(individual + "\t" + getIndividualGenderCode(sModule, individual) + "\t" + (pop == null ? "." : pop) + LINE_SEPARATOR);
            }

            zos.putNextEntry(new ZipEntry(exportName + ".ind"));
            zos.write(indFileContents.toString().getBytes());
            zos.closeEntry();
            
            snpFileWriter.close();
            zos.putNextEntry(new ZipEntry(exportName + ".snp"));
            BufferedReader in = new BufferedReader(new FileReader(snpFile));
            String sLine;
            while ((sLine = in.readLine()) != null) {
                zos.write((sLine + "\n").getBytes());
            }
            in.close();
            zos.closeEntry();

            warningFileWriter.close();
            if (warningFile.length() > 0) {
                zos.putNextEntry(new ZipEntry(exportName + "-REMARKS.txt"));
                int nWarningCount = 0;
                in = new BufferedReader(new FileReader(warningFile));
                while ((sLine = in.readLine()) != null) {
                    zos.write((sLine + "\n").getBytes());
                    nWarningCount++;
                }
                LOG.info("Number of Warnings for export (" + exportName + "): " + nWarningCount);
                in.close();
                zos.closeEntry();
            }
            warningFile.delete();

            zos.finish();
            zos.close();
            progress.setCurrentStepProgress((short) 100);
        } finally {
            if (snpFile != null && snpFile.exists()) {
                snpFile.delete();
            }
        }
    }

    /* (non-Javadoc)
	 * @see fr.cirad.mgdb.exporting.IExportHandler#getStepList()
     */
    @Override
    public List<String> getStepList() {
        return Arrays.asList(new String[]{"Exporting data to EIGENSTRAT format"});
    }
    
	@Override
	public String[] getExportDataFileExtensions() {
		return new String[] {"snp", "ind", "eigenstratgeno"};
	}
}
