/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package pt.webdetails.cdf.dd.render.components;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSON;
import org.apache.commons.jxpath.JXPathContext;
import org.apache.commons.jxpath.Pointer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.pentaho.platform.util.xml.dom4j.XmlDom4JHelper;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.pentaho.platform.api.engine.IFileFilter;
import org.pentaho.platform.api.engine.ISolutionFile;
import org.pentaho.platform.engine.core.system.PentahoSystem;

import pt.webdetails.cdf.dd.CdeSettings;
import pt.webdetails.cdf.dd.DashboardDesignerContentGenerator;
import pt.webdetails.cdf.dd.render.datasources.CdaDatasource;
import pt.webdetails.cdf.dd.render.properties.PropertyManager;
import pt.webdetails.cdf.dd.util.Utils;
import pt.webdetails.cpf.PluginSettings;
import pt.webdetails.cpf.repository.RepositoryAccess;
import pt.webdetails.cpf.repository.RepositoryAccess.FileAccess;

/**
 *
 * @author pdpi
 */
public class ComponentManager
{

  protected static Log logger = LogFactory.getLog(ComponentManager.class);
  
  private static final String PLUGIN_DIR = Utils.joinPath("system",DashboardDesignerContentGenerator.PLUGIN_NAME);

  private static final String BASE_COMPONENTS_DIR = Utils.joinPath(PLUGIN_DIR,"resources","base","components");

  private static final String COMPONENT_FILE = "component.xml";
  
  private static ComponentManager _engine;
  private String basePath = PentahoSystem.getApplicationContext().getSolutionPath("");
  protected Hashtable<String, BaseComponent> componentPool;
  private static final String PACKAGEHEADER = "pt.webdetails.cdf.dd.render.components.";
  private JSON cdaSettings = null;

  protected ComponentManager()
  {
    init(  );
  }

  public static synchronized ComponentManager getInstance()
  {
    if (_engine == null)
    {
      _engine = new ComponentManager();
    }
    return _engine;
  }

  public void refresh()
  {
    // Start by refreshing the dependencies
    PropertyManager.getInstance().refresh();
    init();
  }
  
  private List<File> listAllFiles(File dir, FilenameFilter filter){
    ArrayList<File> results = new ArrayList<File>();

    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          results.addAll(listAllFiles(file, filter));
        } else if (filter.accept(dir, file.getName())) {
          results.add(file);
        }
      }
    }
    return results;
  }

  protected void indexBaseComponents()
  {
    File dir = new File(Utils.joinPath(this.basePath, BASE_COMPONENTS_DIR));
    FilenameFilter xmlFiles = new FilenameFilter()
    {

      public boolean accept(File dir, String name)
      {
        return !name.startsWith(".") && name.endsWith(".xml");
      }
    };
   // String[] files = dir.list(xmlFiles);
    List<File> files = listAllFiles(dir, xmlFiles);
    
    for (File file : files)
    {
      try
      {
        Document doc = XmlDom4JHelper.getDocFromFile(file.getPath(), null);

        // To support multiple definitions on the same file, we'll iterate through all
        // the //DesignerComponent nodes
        List<Node> components = doc.selectNodes("//DesignerComponent");

        for (Node component : components)
        {
          // To figure out whether the component is generic or has a special implementation,
          // we directly look for the class override in the definition
          String className = XmlDom4JHelper.getNodeText("Override", component);
          if (className != null)
          {
            BaseComponent renderer = rendererFromClass(className);
            if (renderer != null)
            {
              componentPool.put(renderer.getName(), renderer);
            }
          }
          else
          {
            GenericComponent renderer = new GenericComponent();
            if (renderer != null)
            {
              try
              {
                renderer.setDefinition(component);
                componentPool.put(renderer.getName(), renderer);
              }
              catch (Exception e)
              {
                Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, e);
              }
            }
          }

        }

      }
      catch (Exception e)
      {
        Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, e);
      }
    }
  }
  
  private void indexCustomComponents(){
    
   for(String componentsDir : CdeSettings.getComponentLocations()){
     indexCustomComponents(componentsDir);
   } 
   
   for(String componentsDir : getExternalComponentLocations()){
     indexCustomComponents(componentsDir);
   }
    
  }
  
  private String[] getExternalComponentLocations(){
    
    final ISolutionFile systemDir = RepositoryAccess.getRepository().getSolutionFile("system", FileAccess.NONE);
    
    ISolutionFile[] systemFolders = systemDir.listFiles(new IFileFilter()
    {
      public boolean accept(ISolutionFile file) {
        return file.isDirectory();
//        return file.isDirectory() && file.retrieveParent().getFileName().equals("system") ||
//               file.getFileName().equals("settings.xml");
      }
    });
    
    ArrayList<ISolutionFile> settingsFiles = new ArrayList<ISolutionFile>();
    
    for(ISolutionFile sysFolder : systemFolders){
      ISolutionFile[] sett = sysFolder.listFiles(new IFileFilter(){

        public boolean accept(ISolutionFile file) {
          return file.getFileName().equals("settings.xml");
        }
        
      });
      for(ISolutionFile s : sett) settingsFiles.add(s);
    }
    
    SettingsReader settingsReader = new SettingsReader();
    
    
    ArrayList<String> componentLocations = new ArrayList<String>();
    for(ISolutionFile file : settingsFiles){
//      if(!file.isDirectory()) {//then is settings.xml
        String pluginName = file.retrieveParent().getFileName();
        settingsReader.setPlugin(pluginName);
        List<String> locations = settingsReader.getComponentLocations();
        if(locations.size() > 0){
          logger.debug("found CDE component declaration in " + pluginName + " [" + locations.size() + "]");
          componentLocations.addAll(locations);
        }
   //  }
    }
    
    return componentLocations.toArray(new String[componentLocations.size()]);
  }
  
  private class SettingsReader extends PluginSettings {

    private String pluginName;
    
    public void setPlugin(String sysFolderName){
      this.pluginName = sysFolderName;
    }
    
    @Override
    public String getPluginSystemDir() {
      return pluginName + "/";
    }

    @Override
    public String getPluginName() {
      return pluginName;
    }
    
    public List<String> getComponentLocations(){
      List<Element> pathElements = getSettingsXmlSection("cde-components/path");
      if(pathElements != null){
        ArrayList<String> solutionPaths = new ArrayList<String>(pathElements.size());
        for(Element pathElement : pathElements){
          solutionPaths.add(pathElement.getText());
        }
        return solutionPaths;//.toArray(new String[solutionPaths.size()]);
      }
      return new ArrayList<String>(0);
    }
    
  }

  private void indexCustomComponents(String dirPath)
  {
    String dirAbsPath = Utils.joinPath(basePath, dirPath);
    File dir = new File(dirAbsPath);

    logger.debug("Loading custom components from: " + dir.toString());
    
    FilenameFilter subFolders = new FilenameFilter()
    {

      public boolean accept(File systemFolder, String name)
      {
        File plugin = new File( Utils.joinPath(systemFolder.getPath(), name, COMPONENT_FILE));
        return plugin.exists() && plugin.canRead();

      }
    };
    String[] files = dir.list(subFolders);
    
    logger.debug(files.length + " sub-folders found");
    
    if (files != null) {
      
      Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
      for (String file : files) {
        try {
          String xmlPath = Utils.joinPath(dir.getPath(), file, COMPONENT_FILE);
          Document doc = XmlDom4JHelper.getDocFromFile(xmlPath, null);

          // To support multiple definitions on the same file, we'll iterate
          // through all the DesignerComponent nodes
          List<Node> components = doc.selectNodes("//DesignerComponent");
          
          if(logger.isDebugEnabled() && components.size() > 0){
            logger.debug(components.size() + " components in " + file);
          }

          for (Node component : components) {

            // To figure out whether the component is generic or has a special
            // implementation, we directly look for the class override in the definition
            String className = XmlDom4JHelper.getNodeText("Override", component);
            if (className != null) {
              BaseComponent renderer = rendererFromClass(className);
              if (renderer != null) {
                componentPool.put(renderer.getName(), renderer);
              }
            } else {
              CustomComponent renderer = new CustomComponent( Utils.joinPath(dirPath,file) );
              if (renderer != null) {
                renderer.setDefinition(component);
                componentPool.put(renderer.getName(), renderer);
              }
            }

          }

        } catch (Exception e) {
          Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, e);
        }
      }
    }
  }

  private void init()
  {
    this.componentPool = new Hashtable<String, BaseComponent>();
    // we need the properties to be initialized. Calling getInstance() is enough.
    PropertyManager.getInstance();
    indexBaseComponents();
    indexCustomComponents();
  }

  private BaseComponent rendererFromClass(String className)
  {
    BaseComponent renderer = null;
    try
    {
      renderer = (BaseComponent) Class.forName(PACKAGEHEADER + className).newInstance();
    }
    catch (ClassNotFoundException ex)
    {
      Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    catch (SecurityException ex)
    {
      Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    catch (InstantiationException ex)
    {
      Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    catch (IllegalAccessException ex)
    {
      Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    catch (IllegalArgumentException ex)
    {
      Logger.getLogger(ComponentManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    return renderer;
  }

  public String getEntry()
  {
    StringBuilder entry = new StringBuilder();
    Collection<BaseComponent> components = componentPool.values();
    for (IComponent render : components)
    {
      entry.append(render.getEntry());
    }
    return entry.toString();
  }

  public String getModel()
  {
    StringBuilder model = new StringBuilder();
    Collection<BaseComponent> components = componentPool.values();
    for (IComponent render : components)
    {
      model.append(render.getModel());
    }
    return model.toString();
  }

  public String getDefinitions()
  {
    StringBuilder defs = new StringBuilder();
    defs.append(PropertyManager.getInstance().getDefinitions());
    defs.append(getEntry());
    defs.append(getModel());
    return defs.toString().replaceAll(",\n(\t*)}", "\n$1}");

  }

  public String getImplementations()
  {
    return "";
  }

  public BaseComponent getRenderer(JXPathContext context)
  {
    String renderType = ((String) context.getValue("type")).replace("Components", "");
    return componentPool.get(renderType);
  }

  public void parseCdaDefinitions(JSON json) throws Exception
  {
    cdaSettings = json;
    final JXPathContext doc = JXPathContext.newContext(json);
    Iterator<Pointer> pointers = doc.iteratePointers("*");
    while (pointers.hasNext())
    {
      Pointer pointer = pointers.next();
      CdaDatasource ds = new CdaDatasource(pointer);
      componentPool.put(ds.getName(), ds);
    }
  }

  public JSON getCdaDefinitions()
  {
    return cdaSettings;
  }
}
