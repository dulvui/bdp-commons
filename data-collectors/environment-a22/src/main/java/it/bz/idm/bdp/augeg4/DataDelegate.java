package it.bz.idm.bdp.augeg4;

import it.bz.idm.bdp.augeg4.dto.AugeG4ProcessedData;
import it.bz.idm.bdp.augeg4.dto.AugeG4RawData;
import it.bz.idm.bdp.augeg4.dto.fromauge.AugeG4ElaboratedDataDto;
import it.bz.idm.bdp.augeg4.dto.toauge.AugeG4ProcessedDataToAugeDto;
import it.bz.idm.bdp.augeg4.dto.tohub.AugeG4ProcessedDataToHubDto;
import it.bz.idm.bdp.augeg4.face.DataConverterAugeFace;
import it.bz.idm.bdp.augeg4.face.DataConverterHubFace;
import it.bz.idm.bdp.augeg4.face.DataDelinearizerFace;
import it.bz.idm.bdp.augeg4.face.DataProcessorFace;
import it.bz.idm.bdp.augeg4.fun.convert.toauge.DataConverterAuge;
import it.bz.idm.bdp.augeg4.fun.convert.tohub.DataConverterHub;
import it.bz.idm.bdp.augeg4.fun.delinearize.DataDelinearizer;
import it.bz.idm.bdp.augeg4.fun.process.DataProcessor;
import it.bz.idm.bdp.augeg4.util.FixedQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class DataDelegate {

    private static final Logger LOG = LogManager.getLogger(DataDelegate.class.getName());

    private static final int MAX_QUEUE_SIZE = 1000;
    private static boolean first_error_stack_logged = false;

    private final DataService dataService;

    private final DataDelinearizerFace delinearizer = new DataDelinearizer();
    private final DataProcessorFace dataProcessor = new DataProcessor();
    private final DataConverterHubFace dataConverterHub = new DataConverterHub();
    private final DataConverterAugeFace dataConverterAuge = new DataConverterAuge();

    private final FixedQueue<AugeG4ProcessedDataToHubDto> queuedDataToHub = new FixedQueue<>(MAX_QUEUE_SIZE);
    private final FixedQueue<AugeG4ProcessedDataToAugeDto> queuedDataToAuge = new FixedQueue<>(MAX_QUEUE_SIZE);

    DataDelegate(DataService dataService) {
        this.dataService = dataService;
    }


    /**
     * - fetches data from retriever
     * - delinearizes the elaborated data from Auge to get the raw data
     * - process data with complex formula
     * - prepares the data for push to Auge
     * - prepares the data for push to HUB
     */
    void fetchData() {
        LOG.info("fetchData() called.");
        try {
            List<AugeG4ElaboratedDataDto> elaboratedData = dataService.getDataRetriever().fetchData();
            if (elaboratedData.isEmpty()) {
                LOG.warn("fetchData() elaboratedData.isEmpty()");
                return;
            }
            List<AugeG4RawData> rawData = delinearizer.delinearize(elaboratedData);
            List<AugeG4ProcessedData> processedData = dataProcessor.process(rawData);
            prepareProcessedDataForAuge(processedData);
            prepareProcessedDataForHub(processedData);
        } catch (Exception e) {
            LOG.error("Processing of elaborated data from Algorab failed: {}.", e.getMessage());
            throw e;
        }
    }

    private void prepareProcessedDataForAuge(List<AugeG4ProcessedData> processedData) {
        try {
            List<AugeG4ProcessedDataToAugeDto> convertedData = dataConverterAuge.convert(processedData);
            queuedDataToAuge.addAll(convertedData);
        } catch (Exception e) {
            LOG.error("Processing of processed data for Auge failed: {}.", e);
        }
    }

    private void prepareProcessedDataForHub(List<AugeG4ProcessedData> processedData) {
        try {
            List<AugeG4ProcessedDataToHubDto> convertedData = dataConverterHub.convert(processedData);
            queuedDataToHub.addAll(convertedData);
            dataService.getStationsDelegate().prepareStationsForHub(convertedData);
        } catch (Exception e) {
            LOG.error("Processing of processed data for HUB failed: {}.", e);
        }
    }

    /**
     * Dequeues converted data and sends it to the HUB
     */
    public void pushData() {
        fetchData();
        pushDataToAuge();
        pushDataToHub();
    }

    private void pushDataToHub() {
        LOG.info("pushDataToHub() called.");
        try {
            List<AugeG4ProcessedDataToHubDto> data = dequeueProcessedDataToHub();
            if (data.isEmpty()) {
                LOG.warn("pushDataToHub() data.isEmpty()");
                return;
            }
            dataService.getDataPusherHub().mapData(data);
            dataService.getDataPusherHub().pushData();
        } catch (Exception e) {
            LOG.error("Push of data to HUB failed: {}.", e.getMessage());
            throw e;
        }
    }

    private List<AugeG4ProcessedDataToHubDto> dequeueProcessedDataToHub() {
        LOG.info("dequeueProcessedDataToHub() called.");
        List<AugeG4ProcessedDataToHubDto> list = new ArrayList<>();
        queuedDataToHub.drainTo(list);
        return list;
    }

    private void pushDataToAuge() {
        LOG.info("pushDataToAuge() called.");
        try {
            List<AugeG4ProcessedDataToAugeDto> data = dequeueProcessedDataToAuge();
            if (data.isEmpty()) {
                LOG.warn("pushDataToAuge() data.isEmpty()");
                return;
            }
            dataService.getDataPusherAuge().pushData(data);
        } catch (Exception e) {
            LOG.error("Push of data to Auge failed: {}.", e.getMessage());
            if (!first_error_stack_logged) {
                e.printStackTrace();
                first_error_stack_logged = true;
            }
            //throw e;
        }
    }

    private List<AugeG4ProcessedDataToAugeDto> dequeueProcessedDataToAuge() {
        LOG.info("dequeueProcessedDataToAuge() called.");
        List<AugeG4ProcessedDataToAugeDto> list = new ArrayList<AugeG4ProcessedDataToAugeDto>();
        queuedDataToAuge.drainTo(list);
        return list;
    }

    public int getDataToAugeCount() {
        return queuedDataToAuge.size();
    }

    public int getDataToHubCount() {
        return queuedDataToHub.size();
    }
}