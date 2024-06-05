/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.jobtype;

import azkaban.jobExecutor.ProcessJob;
import azkaban.utils.JSONUtils;
import azkaban.utils.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class JavaJobRunnerMain {

  public static final String JOB_CLASS = "job.class";
  public static final String DEFAULT_RUN_METHOD = "run";
  public static final String DEFAULT_CANCEL_METHOD = "cancel";

  // This is the Job interface method to get the properties generated by the
  // job.
  public static final String GET_GENERATED_PROPERTIES_METHOD =
      "getJobGeneratedProperties";

  public static final String CANCEL_METHOD_PARAM = "method.cancel";
  public static final String RUN_METHOD_PARAM = "method.run";
  public static final String[] PROPS_CLASSES = new String[] {
    "azkaban.utils.Props",
    "azkaban.common.utils.Props"
  };


  public Logger logger;

  public String cancelMethod;
  public String jobName;
  public Object javaObject;
  private boolean isFinished = false;

  public static void main(String[] args) throws Exception {
    try {
      @SuppressWarnings("unused")
      JavaJobRunnerMain wrapper = new JavaJobRunnerMain();
    } catch (Exception e){
      System.exit(-1);
    }

  }

  public JavaJobRunnerMain() throws Exception {
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        cancelJob();
      }
    });

    try {
      jobName = System.getenv(ProcessJob.JOB_NAME_ENV);
      String propsFile = System.getenv(ProcessJob.JOB_PROP_ENV);
      logger = LoggerFactory.getLogger(JavaJobRunnerMain.class);
      logger.info("Running job " + jobName);
      Properties props = new Properties();
      props.load(new BufferedReader(new FileReader(propsFile)));

      String className = props.getProperty(JOB_CLASS);
      if (className == null) {
        throw new Exception("Class name is not set.");
      }
      logger.info("Class name " + className);

      javaObject = getObject(jobName, className, props, logger);
      if (javaObject == null) {
        logger.info("Could not create java object to run job: " + className);
        throw new Exception("Could not create running object");
      }

      cancelMethod =
          props.getProperty(CANCEL_METHOD_PARAM, DEFAULT_CANCEL_METHOD);

      final String runMethod =
          props.getProperty(RUN_METHOD_PARAM, DEFAULT_RUN_METHOD);
      logger.info("Invoking method " + runMethod);

      logger.info("Proxy check failed, not proxying run.");
      runMethod(javaObject, runMethod);

      isFinished = true;

      // Get the generated properties and store them to disk, to be read
      // by ProcessJob.
      try {
        final Method generatedPropertiesMethod =
            javaObject.getClass().getMethod(GET_GENERATED_PROPERTIES_METHOD,
                new Class<?>[] {});
        Object outputGendProps =
            generatedPropertiesMethod.invoke(javaObject, new Object[] {});
        if (outputGendProps != null) {
          final Method toPropertiesMethod =
              outputGendProps.getClass().getMethod("toProperties",
                  new Class<?>[] {});
          Properties properties =
              (Properties) toPropertiesMethod.invoke(outputGendProps,
                  new Object[] {});

          Props outputProps = new Props(null, properties);
          outputGeneratedProperties(outputProps);
        } else {
          outputGeneratedProperties(new Props());
        }

      } catch (NoSuchMethodException e) {
        logger.info(String.format(
            "Apparently there isn't a method[%s] on object[%s], "
                + "using empty Props object instead.",
            GET_GENERATED_PROPERTIES_METHOD, javaObject));
        outputGeneratedProperties(new Props());
      }
    } catch (Exception e) {
      isFinished = true;
      logger.error("exec job failed .", e);
      throw e;
    }
    System.exit(0);
  }

  private void runMethod(Object obj, String runMethod)
      throws IllegalAccessException, InvocationTargetException,
      NoSuchMethodException {
    obj.getClass().getMethod(runMethod, new Class<?>[] {}).invoke(obj);
  }

  private void outputGeneratedProperties(Props outputProperties) {

    if (outputProperties == null) {
      logger.info("  no gend props");
      return;
    }
    for (String key : outputProperties.getKeySet()) {
      logger
          .info("  gend prop " + key + " value:" + outputProperties.get(key));
    }

    String outputFileStr = System.getenv(ProcessJob.JOB_OUTPUT_PROP_FILE);
    if (outputFileStr == null) {
      return;
    }

    logger.info("Outputting generated properties to " + outputFileStr);

    Map<String, String> properties = new LinkedHashMap<String, String>();
    for (String key : outputProperties.getKeySet()) {
      properties.put(key, outputProperties.get(key));
    }

    Writer writer = null;
    try {
      writer = new BufferedWriter(new FileWriter(outputFileStr));
      JSONUtils.writePropsNoJarDependency(properties, writer);
    } catch (Exception e) {
      throw new RuntimeException("Unable to store output properties to: "
          + outputFileStr);
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
        }
      }
    }
  }

  public void cancelJob() {
    if (isFinished) {
      return;
    }
    logger.info("Attempting to call cancel on this job");
    if (javaObject == null) {
      return;
    }

    Method method = null;
    try {
      method = javaObject.getClass().getMethod(cancelMethod);
    } catch (SecurityException e) {
    } catch (NoSuchMethodException e) {
    }

    if (method != null) {
      try {
        method.invoke(javaObject);
      } catch (Exception e) {
        if (logger != null) {
          logger.error("Cancel method failed! ", e);
        }
      }
    } else {
      throw new RuntimeException("Job " + jobName
          + " does not have cancel method " + cancelMethod);
    }
  }


  private static Object getObject(String jobName, String className,
      Properties properties, Logger logger) throws Exception {

    Class<?> runningClass =
        JavaJobRunnerMain.class.getClassLoader().loadClass(className);

    if (runningClass == null) {
      throw new Exception("Class " + className
          + " was not found. Cannot run job.");
    }

    Class<?> propsClass = null;
    for (String propClassName : PROPS_CLASSES) {
      try {
        propsClass =
            JavaJobRunnerMain.class.getClassLoader().loadClass(propClassName);
      } catch (ClassNotFoundException e) {
      }

      if (propsClass != null
          && getConstructor(runningClass, String.class, propsClass) != null) {
        // is this the props class
        break;
      }
      propsClass = null;
    }

    Object obj = null;
    if (propsClass != null
        && getConstructor(runningClass, String.class, propsClass) != null) {
      // Create props class
      Constructor<?> propsCon =
          getConstructor(propsClass, propsClass, Properties[].class);
      Object props =
          propsCon.newInstance(null, new Properties[] { properties });

      Constructor<?> con =
          getConstructor(runningClass, String.class, propsClass);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance(jobName, props);
    } else if (getConstructor(runningClass, String.class, Properties.class) != null) {
      Constructor<?> con =
          getConstructor(runningClass, String.class, Properties.class);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance(jobName, properties);
    } else if (getConstructor(runningClass, String.class, Map.class) != null) {
      Constructor<?> con =
          getConstructor(runningClass, String.class, Map.class);
      logger.info("Constructor found " + con.toGenericString());

      HashMap<Object, Object> map = new HashMap<Object, Object>();
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        map.put(entry.getKey(), entry.getValue());
      }
      obj = con.newInstance(jobName, map);
    } else if (getConstructor(runningClass, String.class) != null) {
      Constructor<?> con = getConstructor(runningClass, String.class);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance(jobName);
    } else if (getConstructor(runningClass) != null) {
      Constructor<?> con = getConstructor(runningClass);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance();
    } else {
      logger.error("Constructor not found. Listing available Constructors.");
      for (Constructor<?> c : runningClass.getConstructors()) {
        logger.info(c.toGenericString());
      }
    }
    return obj;
  }

  private static Constructor<?> getConstructor(Class<?> c, Class<?>... args) {
    try {
      Constructor<?> cons = c.getConstructor(args);
      return cons;
    } catch (NoSuchMethodException e) {
      return null;
    }
  }
}