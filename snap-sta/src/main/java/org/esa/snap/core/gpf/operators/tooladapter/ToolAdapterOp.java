/*
 * Copyright (C) 2014-2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 *  with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.snap.core.gpf.operators.tooladapter;

import com.bc.ceres.binding.Property;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.apache.velocity.app.Velocity;
import org.esa.snap.core.dataio.*;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.descriptor.SystemVariable;
import org.esa.snap.core.gpf.descriptor.TemplateParameterDescriptor;
import org.esa.snap.core.gpf.descriptor.ToolAdapterOperatorDescriptor;
import org.esa.snap.core.gpf.descriptor.ToolParameterDescriptor;
import org.esa.snap.core.gpf.descriptor.template.TemplateContext;
import org.esa.snap.core.gpf.descriptor.template.TemplateException;
import org.esa.snap.core.gpf.descriptor.template.TemplateFile;
import org.esa.snap.core.gpf.internal.OperatorContext;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.utils.PrivilegedAccessor;

import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Tool Adapter operator
 *
 * @author Lucian Barbulescu
 * @author Cosmin Cara
 */
@OperatorMetadata(alias = "ToolAdapterOp",
        category = "Tools",
        version = "1.0",
        description = "Tool Adapter Operator")
public class ToolAdapterOp extends Operator {

    private static final String INTERMEDIATE_PRODUCT_NAME = "interimProduct";
    private static final String[] DEFAULT_EXTENSIONS = { ".tif", ".tiff", ".nc", ".hdf", ".pgx", ".png", ".gif", ".jpg", ".bmp", ".pnm", ".pbm", ".pgm", ".ppm", ".jp2" };

    /**
     * Consume the output created by a tool.
     */
    private ProcessOutputConsumer consumer;

    /**
     * Stop the tool's execution.
     */
    private volatile boolean isStopped;

    private volatile boolean wasCancelled;

    private ToolAdapterOperatorDescriptor descriptor;

    private ProgressMonitor progressMonitor;

    private List<File> intermediateProductFiles;

    private OperatorContext accessibleContext;

    private List<String> errorMessages;

    private TemplateContext lastPostContext;

    /**
     * Constructor.
     */
    public ToolAdapterOp() {
        super();
        errorMessages = new ArrayList<>();
        this.consumer = null;
        Logger logger = getLogger();
        try {
            accessibleContext = (OperatorContext) PrivilegedAccessor.getValue(this, "context");
        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
        Velocity.init();
        intermediateProductFiles = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (Level.SEVERE.equals(record.getLevel())) {
                    errorMessages.add(record.getMessage());
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() throws SecurityException { }
        });
    }

    /**
     * Registers a consumer for the tool's output.
     *
     * @param consumer the output consumer.
     */
    public void setConsumer(ProcessOutputConsumer consumer) {
        this.consumer = consumer;
        if (consumer != null) {
            this.consumer.setLogger(getLogger());
        }
    }

    public void setProgressMonitor(ProgressMonitor monitor) { this.progressMonitor = monitor; }

    /**
     * Command to isStopped the tool.
     * <p>
     * This method is synchronized.
     * </p>
     */
    public void stop() {
        this.isStopped = true;
    }

    /**
     * Check if a isStopped command was issued.
     * <p>
     * This method is synchronized.
     * </p>
     *
     * @return true if the execution of the tool must be stopped.
     */
    private boolean isStopped() {
        return this.isStopped;
    }

    public void setAdapterFolder(File folder) {
        /*
      The folder where the tool descriptors reside.
     */
        File adapterFolder = folder;
    }

    /**
     * Gets the list of errors that have been produced during external tool execution
     *
     * @return  A list of error messages. The list is empty if no error has occured.
     */
    public List<String> getErrors() {
        return errorMessages;
    }

    /**
     * Initialise and run the defined tool.
     * <p>
     * This method will block until the tool finishes its execution.
     * </p>
     *
     * @throws OperatorException
     */
    @Override
    public void initialize() throws OperatorException {
        Date currentTime = new Date();
        try {
            if (descriptor == null) {
                descriptor = ((ToolAdapterOperatorDescriptor) accessibleContext.getOperatorSpi().getOperatorDescriptor());
            }
            if (this.progressMonitor != null) {
                this.progressMonitor.beginTask("Executing " + this.descriptor.getName(), 100);
            }
            if (this.consumer == null) {
                this.consumer = new DefaultOutputConsumer(descriptor.getProgressPattern(), descriptor.getErrorPattern(), descriptor.getStepPattern(), this.progressMonitor);
                this.consumer.setLogger(getLogger());
            }
            if (errorMessages != null) {
                errorMessages.clear();
            }
            validateDescriptor();
            if (!isStopped) {
                beforeExecute();
            }
            if (!isStopped) {
                int ret = execute();
                if (ret != 0) {
                    this.consumer.consumeOutput(String.format("Process exited with value %d", ret));
                }
            }
            if (this.consumer != null) {
                Date finalDate = new Date();
                this.consumer.consumeOutput("Finished tool execution in " + (finalDate.getTime() - currentTime.getTime()) / 1000 + " seconds");
            }
        } finally {
            try {
                if (!wasCancelled) {
                    postExecute();
                }
            } finally {
                if (this.progressMonitor != null) {
                    this.progressMonitor.done();
                }
            }
        }
    }

    public List<String> getExecutionOutput() {
        return this.consumer.getProcessOutput();
    }

    public Product getResult() {
        return accessibleContext.isInitialized() ?
            accessibleContext.getTargetProduct() : null;
    }

    /**
     * Verify that the data provided withing the operator descriptor is valid.
     *
     * @throws OperatorException in case of an error
     */
    private void validateDescriptor() throws OperatorException {

        //Get the tool file
        File toolFile = descriptor.resolveVariables(descriptor.getMainToolFileLocation());
        if (toolFile == null) {
            throw new OperatorException("Tool file not defined!");
        }
        // check if the tool file exists
        if (!toolFile.exists() || !toolFile.isFile()) {
            throw new OperatorException(String.format("Invalid tool file: '%s'!", toolFile.getAbsolutePath()));
        }
    }

    /**
     * Fill the templates with data and prepare the source product.
     *
     * @throws OperatorException in case of an error
     */
    private void beforeExecute() throws OperatorException {
        reportProgress("Interpreting pre-execution template");
        descriptor.getToolParameterDescriptors().stream().filter(parameter -> parameter.getParameterType().equals(ToolAdapterConstants.TEMPLATE_BEFORE_MASK))
                .forEach(parameter -> {
                    try {
                        transformTemplateParameter((TemplateParameterDescriptor) parameter);
                    } catch (IOException | TemplateException e) {
                        getLogger().severe(String.format("Error processing template before execution for parameter [%s]", parameter.getName()));
                    }
                });
        if (descriptor.shouldWriteBeforeProcessing()) {
            String sourceFormatName = descriptor.getProcessingWriter();
            if (sourceFormatName != null) {
                reportProgress(String.format("Converting source product to %s", sourceFormatName));
                ProductIOPlugInManager registry = ProductIOPlugInManager.getInstance();
                Iterator<ProductWriterPlugIn> writerPlugIns = registry.getWriterPlugIns(sourceFormatName);
                ProductWriterPlugIn writerPlugIn = writerPlugIns.next();
                final Product[] selectedProducts = getSourceProducts();
                String sourceDefaultExtension = writerPlugIn.getDefaultFileExtensions()[0];
                for (Product selectedProduct : selectedProducts) {
                    if (!sourceDefaultExtension.equalsIgnoreCase(selectedProduct.getProductReader().getReaderPlugIn().getDefaultFileExtensions()[0])) {
                        File outFile = new File(descriptor.resolveVariables(descriptor.getWorkingDir()), INTERMEDIATE_PRODUCT_NAME + sourceDefaultExtension);
                        boolean hasDeleted = false;
                        while (outFile.exists() && !hasDeleted) {
                            hasDeleted = outFile.canWrite() && outFile.delete();
                            if (!hasDeleted) {
                                getLogger().warning(String.format("Could not delete previous temporary image %s", outFile.getName()));
                                outFile = new File(descriptor.resolveVariables(descriptor.getWorkingDir()), INTERMEDIATE_PRODUCT_NAME + "_" + new Date().getTime() + sourceDefaultExtension);
                            }
                        }
                        Product interimProduct = new Product(outFile.getName(), selectedProduct.getProductType(),
                                selectedProduct.getSceneRasterWidth(), selectedProduct.getSceneRasterHeight());
                        try {
                            ProductUtils.copyProductNodes(selectedProduct, interimProduct);
                            for (Band sourceBand : selectedProduct.getBands()) {
                                ProductUtils.copyBand(sourceBand.getName(), selectedProduct, interimProduct, true);
                            }
                            ProductIO.writeProduct(interimProduct, outFile, sourceFormatName, true, SubProgressMonitor.create(progressMonitor, 50));
                        } catch (IOException e) {
                            getLogger().severe(String.format("Cannot write to %s format", sourceFormatName));
                            stop();
                        } finally {
                            try {
                                interimProduct.closeIO();
                                interimProduct.dispose();
                            } catch (IOException ignored) {
                            }
                            reportProgress("Product conversion finished");
                        }
                        if (outFile.exists()) {
                            intermediateProductFiles.add(outFile);
                        } else {
                            stop();
                        }
                    }
                }
            }
        }
    }

    /**
     * Run the tool.
     *
     * @return the return value of the process.
     * @throws OperatorException in case of an error.
     */
    private int execute() throws OperatorException {
        Process process = null;
        BufferedReader outReader = null;
        int ret = -1;
        try {
            reportProgress("Starting tool execution");
            List<String> cmdLine = getCommandLineTokens();
            logCommandLine(cmdLine);
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            //redirect the error of the tool to the standard output
            pb.redirectErrorStream(true);
            //set the working directory
            pb.directory(descriptor.resolveVariables(descriptor.getWorkingDir()));
            pb.environment().putAll(descriptor.getVariables()
                                                .stream()
                                                .collect(Collectors.toMap(
                                                        SystemVariable::getKey,
                                                        SystemVariable::getValue))
            );
            //start the process
            process = pb.start();
            //get the process output
            outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (!isStopped()) {
                while (!isStopped && outReader.ready()) {
                    //read the process output line by line
                    String line = outReader.readLine();
                    //consume the line if possible
                    if (line != null && !"".equals(line.trim())) {
                        this.consumer.consumeOutput(line);
                    }
                }
                // check if the project finished execution
                if (!process.isAlive()) {
                    //isStopped the loop
                    stop();
                } else {
                    //yield the control to other threads
                    Thread.yield();
                }
            }
            ret = process.exitValue();
        } catch (IOException e) {
            wasCancelled = true;
            throw new OperatorException(String.format("%s execution was interrupted [%s]",descriptor.getName(), e));
        } finally {
            if (process != null) {
                // if the process is still running, force it to isStopped
                if (process.isAlive()) {
                    //destroy the process
                    process.destroyForcibly();
                }
                try {
                    //wait for the project to end.
                    ret = process.waitFor();
                } catch (InterruptedException e) {
                    //noinspection ThrowFromFinallyBlock
                    throw new OperatorException(String.format("Error stopping %s [%s]", descriptor.getName(), e));
                }

                //close the reader
                closeStream(outReader);
                //close all streams
                closeStream(process.getErrorStream());
                closeStream(process.getInputStream());
                closeStream(process.getOutputStream());
            }
        }

        return ret;
    }

    /**
     * Load the result of the tool's execution.
     *
     * @throws OperatorException in case of an error
     */
    private void postExecute() throws OperatorException {
        for(ToolParameterDescriptor parameter : descriptor.getToolParameterDescriptors()){
            if(parameter.getParameterType().equals(ToolAdapterConstants.TEMPLATE_AFTER_MASK)){
                try {
                    transformTemplateParameter((TemplateParameterDescriptor) parameter);
                } catch (IOException | TemplateException e) {
                    throw new OperatorException("Error processing template after execution for parameter: '" + parameter.getName() + "'");
                }
            }
        }
        reportProgress("Trying to open the new product");
        File input = descriptor.resolveVariables((File) getParameter(ToolAdapterConstants.TOOL_TARGET_PRODUCT_FILE));
        if (input == null) {
            input = descriptor.resolveVariables((File) this.lastPostContext.getValue(ToolAdapterConstants.TOOL_TARGET_PRODUCT_FILE));
        }
        this.lastPostContext = null;
        if (input != null) {
            try {
                intermediateProductFiles.stream().filter(intermediateProductFile -> intermediateProductFile != null && intermediateProductFile.exists())
                                                 .filter(intermediateProductFile -> !(intermediateProductFile.canWrite() && intermediateProductFile.delete()))
                                                 .forEach(intermediateProductFile -> getLogger().warning(String.format("Temporary image %s could not be deleted", intermediateProductFile.getName())));
                if (input.isDirectory()) {
                    input = selectCandidateRasterFile(input);
                }
                getLogger().info(String.format("Trying to open %s", input.getAbsolutePath()));
                Product target;
                try {
                    target = ProductIO.readProduct(input);
                    for (Band band : target.getBands()) {
                        ImageManager.getInstance().getSourceImage(band, 0);
                    }
                    setTargetProduct(target);
                } catch (Exception inner) {
                    getLogger().warning(String.format("Opening target product by guessing the plugin failed [%s]. Trying by extension.", inner.getMessage()));
                    List<ProductReaderPlugIn> plugInsByExtension = ToolAdapterIO.getReaderPlugInsByExtension(FileUtils.getExtension(input));
                    ProductReaderPlugIn readerPlugIn;
                    if (plugInsByExtension.size() > 0 && (readerPlugIn = plugInsByExtension.get(0)) != null) {
                        ProductReader productReader = readerPlugIn.createReaderInstance();
                        target = productReader.readProductNodes(input, null);
                        setTargetProduct(target);
                    }
                }
            } catch (IOException e) {
                throw new OperatorException("Error reading product '" + input.getPath() + "'");
            }
        }
        if (this.consumer != null && this.consumer instanceof DefaultOutputConsumer) {
            ((DefaultOutputConsumer) this.consumer).close();
        }
    }

    /**
     * Close any stream without triggering exceptions.
     *
     * @param stream input or output stream.
     */
    private void closeStream(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                //nothing to do.
            }
        }
    }

    /**
     * Add the tool's command line to the log.
     *
     * @param cmdLine the command line
     */
    private void logCommandLine(List<String> cmdLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("Executing tool '").append(this.descriptor.getName()).append("' with command line: ");
        sb.append('\'').append(cmdLine.get(0));
        for (int i = 1; i < cmdLine.size(); i++) {
            sb.append(' ').append(cmdLine.get(i));
        }
        sb.append('\'');

        getLogger().log(Level.INFO, sb.toString());
    }

    /**
     * Build the list of command line parameters.
     * <p>
     * If no command line template defined then only the tool's file is returned
     * </p>
     *
     * @return the list of command line parameters
     * @throws OperatorException in case of an error.
     */
    private List<String> getCommandLineTokens() throws OperatorException {
        final List<String> tokens = new ArrayList<>();
        TemplateFile template = ((ToolAdapterOperatorDescriptor) (getSpi().getOperatorDescriptor())).getTemplate();
        if (template != null) {
            tokens.add(descriptor.resolveVariables(descriptor.getMainToolFileLocation()).getAbsolutePath());
            try {
                tokens.addAll(descriptor.getTemplateEngine().getLines(template, extractParameters()));
            } catch (TemplateException e) {
                throw new OperatorException(e);
            }
        } else {
            throw new OperatorException("Invalid template [null]");
        }
        return tokens;
    }

    private Map<String, Object> extractParameters(){
        Map<String, Object> parameters = new HashMap<>();
        Property[] params = accessibleContext.getParameterSet().getProperties();
        for (Property param : params) {
            Optional<ToolParameterDescriptor> descriptor = this.descriptor.getToolParameterDescriptors().stream().filter(d -> d.getName().equals(param.getName())).findFirst();
            if (!descriptor.isPresent()) {
                throw new OperatorException("Unexpected parameter: " + param.getName());
            }
            ToolParameterDescriptor paramDescriptor = descriptor.get();
            if (paramDescriptor.isTemplateParameter()) {
                try {
                    String transformedFile = transformTemplateParameter((TemplateParameterDescriptor) paramDescriptor);
                    parameters.put(param.getName(), transformedFile);
                } catch (IOException | TemplateException ex) {
                    throw new OperatorException("Error on transforming template for parameter '" + paramDescriptor.getName());
                }
            } else {
                String paramName = param.getName();
                Object paramValue = param.getValue();
                if (ToolAdapterConstants.TOOL_TARGET_PRODUCT_FILE.equals(paramName)) {
                    paramValue = getNextFileName(this.descriptor.resolveVariables((File) paramValue));
                }
                if (param.getType().isArray()) {
                    paramValue = StringUtils.arrayToString(paramValue, "\n");
                }
                if (paramDescriptor.isNotEmpty() || paramDescriptor.isNotNull() || (paramValue != null && !paramValue.toString().isEmpty())) {
                    parameters.put(paramName, paramValue);
                }
            }
        }

        Product[] sourceProducts = getSourceProducts();
        parameters.put(ToolAdapterConstants.TOOL_SOURCE_PRODUCT_ID,
                sourceProducts.length == 1 ? sourceProducts[0] : sourceProducts);
        File[] rasterFiles = new File[sourceProducts.length];
        for (int i = 0; i < sourceProducts.length; i++) {
            File productFile = intermediateProductFiles.size() == sourceProducts.length ?
                                    intermediateProductFiles.get(i) :
                                    sourceProducts[i].getFileLocation();
            rasterFiles[i] = productFile.isFile() ? productFile : selectCandidateRasterFile(productFile);
        }
        parameters.put(ToolAdapterConstants.TOOL_SOURCE_PRODUCT_FILE,
                rasterFiles.length == 1 ? rasterFiles[0] : rasterFiles);
        return parameters;
    }

    private String transformTemplateParameter(TemplateParameterDescriptor parameter) throws IOException, TemplateException {
        Map<String, Object> parameters = new HashMap<>();
        Property[] params = accessibleContext.getParameterSet().getProperties();
        for (Property param : params) {
            String paramName = param.getName();
            Object paramValue = param.getValue();
            if (ToolAdapterConstants.TOOL_TARGET_PRODUCT_FILE.equals(paramName)) {
                paramValue = getNextFileName(this.descriptor.resolveVariables((File) paramValue));
            }
            if (param.getType().isArray()) {
                paramValue = StringUtils.arrayToString(paramValue, "\n");
            }
            parameters.put(paramName, paramValue);
        }

        Product[] sourceProducts = getSourceProducts();
        parameters.put(ToolAdapterConstants.TOOL_SOURCE_PRODUCT_ID,
                sourceProducts.length == 1 ? sourceProducts[0] : sourceProducts);
        File[] rasterFiles = new File[sourceProducts.length];
        for (int i = 0; i < sourceProducts.length; i++) {
            File productFile = intermediateProductFiles.size() == sourceProducts.length ?
                    intermediateProductFiles.get(i) :
                    sourceProducts[i].getFileLocation();
            rasterFiles[i] = productFile.isFile() ? productFile : selectCandidateRasterFile(productFile);
        }
        parameters.put(ToolAdapterConstants.TOOL_SOURCE_PRODUCT_FILE,
                rasterFiles.length == 1 ? rasterFiles[0] : rasterFiles);

        String result = parameter.executeTemplate(parameters);
        String separatorChar = ToolAdapterConstants.OPERATOR_TEMP_FILES_SEPARATOR;
        String dateFormatted = DateFormat.getDateInstance(
                DateFormat.SHORT,
                Locale.ENGLISH).format(new Date());
        dateFormatted = dateFormatted + separatorChar + DateFormat.getTimeInstance(
                DateFormat.DEFAULT,
                Locale.ENGLISH).format(new Date()).replace(":", separatorChar);
        dateFormatted = dateFormatted.replace("/", separatorChar).replace(" ", separatorChar);
        String newFileName = parameter.getTemplate().getFileName() + "_result_" + dateFormatted;
        ToolAdapterIO.saveFileContent(new File(descriptor.resolveVariables(descriptor.getWorkingDir()), newFileName), result);
        this.lastPostContext = parameter.getLastContext();
        return newFileName;
    }

    private File selectCandidateRasterFile(File folder) {
        File rasterFile = null;
        List<File> candidates = getRasterFiles(folder);
        int numFiles = candidates.size() - 1;
        if (numFiles >= 0) {
            candidates.sort(Comparator.comparingLong(File::length));
            rasterFile = candidates.get(numFiles);
            getLogger().info(rasterFile.getName() + " was selected as raster file");
        }
        return rasterFile;
    }

    private List<File> getRasterFiles(File folder) {
        List<File> rasters = new ArrayList<>();
        for (String extension : DEFAULT_EXTENSIONS) {
            File[] files = folder.listFiles((File dir, String name) -> name.endsWith(extension));
            if (files != null) {
                rasters.addAll(Arrays.asList(files));
            }
        }
        File[] subFolders = folder.listFiles(File::isDirectory);
        if (subFolders != null) {
            for (File subFolder : subFolders) {
                List<File> subCandidates = getRasterFiles(subFolder);
                if (subCandidates != null) {
                    rasters.addAll(subCandidates);
                }
            }
        }
        return rasters;
    }

    private void reportProgress(String message) {
        if (this.progressMonitor != null) {
            this.progressMonitor.setTaskName(message);
        }
    }

    private File getNextFileName(File file) {
        if (file != null) {
            int counter = 1;
            File initial = file;
            while (file.exists()) {
                file = new File(initial.getParent(),
                        FileUtils.getFilenameWithoutExtension(initial) + "_" + String.valueOf(counter++) +
                                FileUtils.getExtension(initial));
            }
        }
        return file;
    }
}
