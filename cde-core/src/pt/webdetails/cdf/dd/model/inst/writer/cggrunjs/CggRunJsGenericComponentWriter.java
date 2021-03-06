/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package pt.webdetails.cdf.dd.model.inst.writer.cggrunjs;
import java.io.ByteArrayInputStream;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import pt.webdetails.cdf.dd.model.core.Thing;
import pt.webdetails.cdf.dd.model.core.UnsupportedThingException;
import pt.webdetails.cdf.dd.model.core.writer.IThingWriteContext;
import pt.webdetails.cdf.dd.model.core.writer.IThingWriter;
import pt.webdetails.cdf.dd.model.core.writer.IThingWriterFactory;
import pt.webdetails.cdf.dd.model.core.writer.ThingWriteException;
import pt.webdetails.cdf.dd.model.core.writer.js.JsWriterAbstract;
import pt.webdetails.cdf.dd.model.inst.Dashboard;
import pt.webdetails.cdf.dd.model.inst.DataSourceComponent;
import pt.webdetails.cdf.dd.model.inst.GenericComponent;
import pt.webdetails.cdf.dd.model.inst.writer.cdfrunjs.CdfRunJsThingWriterFactory;
import pt.webdetails.cdf.dd.model.inst.writer.cdfrunjs.dashboard.CdfRunJsDashboardWriteContext;
import pt.webdetails.cdf.dd.model.inst.writer.cdfrunjs.dashboard.CdfRunJsDashboardWriteOptions;
import pt.webdetails.cdf.dd.util.Utils;
import pt.webdetails.cpf.repository.api.IUserContentAccess;
import pt.webdetails.cpf.utils.CharsetHelper;
import pt.webdetails.cdf.dd.model.meta.ComponentType;
import pt.webdetails.cdf.dd.model.meta.Resource;

/**
 * @author dcleao
 */
public class CggRunJsGenericComponentWriter extends JsWriterAbstract implements IThingWriter
{
  private static final Log    _logger = LogFactory.getLog(CggRunJsGenericComponentWriter.class);
  private static final String CGG_EXTENSION = ".js";
  
  public void write(Object output, IThingWriteContext context, Thing t) throws ThingWriteException
  {
    this.write((IUserContentAccess) output, (CggRunJsDashboardWriteContext)context, (GenericComponent)t);
  }
  
  public void write(IUserContentAccess access, CggRunJsDashboardWriteContext context, GenericComponent comp) throws ThingWriteException
  {
    String dashboardFilePath = context.getDashboard().getSourcePath();
    String dashboardFileDir  = FilenameUtils.getFullPath(dashboardFilePath);
    String dashboardFileName = FilenameUtils.getName(dashboardFilePath);
    
    StringBuilder out = new StringBuilder();
    
    out.append("lib('protovis-bundle.js');")
       .append(NEWLINE)
       .append(NEWLINE);
    
    this.renderChart(out, context, comp, dashboardFileDir);
    out.append(NEWLINE);
    
    this.renderDatasource(out, context, comp);
    out.append(NEWLINE)
       .append(NEWLINE);
    
    String chartName = comp.getName();
    String compVarName = "render_" + chartName;
    
    out.append("cgg.render(").append(compVarName).append(");")
       .append(NEWLINE);
    
    String chartScript = out.toString();
    
    writeFile(access, chartScript, dashboardFileDir, chartName, dashboardFileName);
    // Legacy support requires to keep generating dashboard unqualified scripts as well
    writeFile(access, chartScript, dashboardFileDir, chartName,  null);
  }
  
   private void writeFile(
		   IUserContentAccess access, 
           String chartScript, 
           String dashboardFileDir,
           String chartName, 
           String dashboadFilName)
  {
    try
    {
      String prefix   = dashboadFilName == null ? "" : dashboadFilName.substring(0, dashboadFilName.lastIndexOf('.')) + '_'; 
      String fileName = prefix + chartName + CGG_EXTENSION;
      
      byte[] content = chartScript.getBytes(CharsetHelper.getEncoding());
      
      if(!access.saveFile(Utils.joinPath(dashboardFileDir, fileName), new ByteArrayInputStream(content))) {
        _logger.error("Failed to write CGG script file for chart '" + chartName + "'.");
      }
    } catch(Exception ex) {
      _logger.error("Failed to write script file for '" + chartName + "': " + ex.getCause().getMessage());
    }
  }
   
  private void renderChart(
          StringBuilder out, 
          CggRunJsDashboardWriteContext context, 
          GenericComponent comp,
          String dashDir) throws ThingWriteException
  {
    ComponentType compType = comp.getMeta();
    
    for(Resource resource : compType.getResources())
    {
      Resource.Type resType = resource.getType();
      switch(resType) 
      {
        case RAW:
          out.append(NEWLINE);
          out.append(resource.getSource());
          out.append(NEWLINE);
          break;
        
        case SCRIPT:
          out.append(NEWLINE);
          out.append("load('");
          out.append(makeDashRelative(resource.getSource(), dashDir)); 
          out.append("');");
          out.append(NEWLINE);
          break;
      }
    }
    
    // Implementation
    String srcImpl = compType.getImplementationPath();
    if(StringUtils.isNotEmpty(srcImpl))
    {
      out.append(NEWLINE);
      out.append("load('");
      out.append(makeDashRelative(srcImpl, dashDir));
      out.append("');");
      out.append(NEWLINE);
    }

    // ---------------
    // TODO: HACK: Delegate writing the component definition to the corresponding CdfRunJs writer
    // Should this be done differently?
    IThingWriterFactory writerFactory = new CdfRunJsThingWriterFactory();
    IThingWriter compWriter;
    try
    {
      compWriter = writerFactory.getWriter(comp);
    }
    catch(UnsupportedThingException ex)
    {
      throw new ThingWriteException("Error while obtaining a writer for rendering the generic component.", ex);
    }
    
    // Options are kind of irrelevant in this context, 
    // as we're only rendering one component, not a dashboard (and not a widget component).
    CdfRunJsDashboardWriteOptions options = new CdfRunJsDashboardWriteOptions(false, false, "", "");
    
    // Idem
    CdfRunJsDashboardWriteContext writeContext = 
      new CdfRunJsDashboardWriteContext(
            writerFactory, 
            /*indent*/"", 
            /*bypassCacheRead*/true, 
            context.getDashboard(), 
            options);
    
    compWriter.write(out, writeContext, comp);
  }
  
  // Both are solution dir relative.
  private static String makeDashRelative(String path, String dashDir)
  {
    // resource -> system/pentaho-cdf-dd/resources/custom/components/InteractiveCosmos/interactiveCosmos.js
    // dashDir  -> cde/ <dashboard-is-here>
    // resource must be found relative to dashDir:
    // -> ../system/pentaho-cdf-dd/...
    // Find how many times to go back
    // Remove leading and trailing /
    dashDir = dashDir.replaceAll("^[/\\\\]*(.*?)[/\\\\]*$", "$1");
    
    if(!dashDir.isEmpty())
    {
      int count = dashDir.split("[/\\\\]").length;
      for(int i = 0 ; i < count ; i++) 
      {
        path = "../" + path;
      }
    }
    
    return path;
  }
  private void renderDatasource(StringBuilder out, CggRunJsDashboardWriteContext context, GenericComponent comp) throws ThingWriteException
  {
    Dashboard dash = context.getDashboard();
    String dataSourceName = comp.tryGetPropertyValue("dataSource", null);
    if(StringUtils.isNotEmpty(dataSourceName))
    {
      DataSourceComponent dsComp = dash.getDataSource(dataSourceName);
      
      IThingWriterFactory factory = context.getFactory();
      IThingWriter dsWriter;
      try
      {
        dsWriter = factory.getWriter(dsComp);
      }
      catch (UnsupportedThingException ex)
      {
        throw new ThingWriteException(ex);
      }
      
      CggRunJsComponentWriteContext compContext = new CggRunJsComponentWriteContext(factory, dash, comp);
      dsWriter.write(out, compContext, dsComp);
    }
  }
}
