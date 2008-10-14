/*
 * soapUI, copyright (C) 2004-2008 eviware.com
 *
 * soapUI is free software; you can redistribute it and/or modify it under the
 * terms of version 2.1 of the GNU Lesser General Public License as published by
 * the Free Software Foundation.
 *
 * soapUI is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details at gnu.org.
 */

package com.eviware.soapui.impl.wsdl.teststeps;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.config.*;
import com.eviware.soapui.impl.wsdl.AbstractWsdlModelItem;
import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlSubmitContext;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockOperation;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockResponse;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockResult;
import com.eviware.soapui.impl.wsdl.mock.WsdlMockRunner;
import com.eviware.soapui.impl.wsdl.panels.mockoperation.WsdlMockResultMessageExchange;
import com.eviware.soapui.impl.wsdl.support.ModelItemIconAnimator;
import com.eviware.soapui.impl.wsdl.support.assertions.AssertableConfig;
import com.eviware.soapui.impl.wsdl.support.assertions.AssertedXPathsContainer;
import com.eviware.soapui.impl.wsdl.support.assertions.AssertionsSupport;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestRunContext;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.TestAssertionRegistry;
import com.eviware.soapui.impl.wsdl.teststeps.assertions.TestAssertionRegistry.AssertableType;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.iface.Interface;
import com.eviware.soapui.model.iface.Operation;
import com.eviware.soapui.model.iface.SubmitContext;
import com.eviware.soapui.model.mock.MockResult;
import com.eviware.soapui.model.propertyexpansion.PropertyExpansion;
import com.eviware.soapui.model.propertyexpansion.PropertyExpansionUtils;
import com.eviware.soapui.model.support.*;
import com.eviware.soapui.model.testsuite.*;
import com.eviware.soapui.model.testsuite.AssertionError;
import com.eviware.soapui.model.testsuite.TestStepResult.TestStepStatus;
import com.eviware.soapui.support.resolver.ChangeOperationResolver;
import com.eviware.soapui.support.resolver.ImportInterfaceResolver;
import com.eviware.soapui.support.resolver.ResolveContext;
import com.eviware.soapui.support.resolver.defaultaction.RemoveTestStepDefaultResolveAction;
import com.eviware.soapui.support.types.StringToStringMap;
import org.apache.log4j.Logger;

import javax.swing.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;

public class WsdlMockResponseTestStep extends WsdlTestStepWithProperties implements OperationTestStep,
        PropertyChangeListener, Assertable
{
   private final static Logger log = Logger.getLogger( WsdlMockResponseTestStep.class );

   public static final String STATUS_PROPERTY = WsdlMockResponseTestStep.class.getName() + "@status";
   public static final String TIMEOUT_PROPERTY = WsdlMockResponseTestStep.class.getName() + "@timeout";

   private MockResponseStepConfig mockResponseStepConfig;
   private MockResponseConfig mockResponseConfig;
   private WsdlMockOperation mockOperation;
   private WsdlTestMockService mockService;
   private WsdlMockRunner mockRunner;
   private WsdlMockResponse mockResponse;

   private AssertionsSupport assertionsSupport;
   private InternalMockRunListener listener;

   private final InternalProjectListener projectListener = new InternalProjectListener();
   private final InternalInterfaceListener interfaceListener = new InternalInterfaceListener();
   private WsdlInterface iface;
   private AssertionStatus oldStatus;

   private ModelItemIconAnimator<WsdlMockResponseTestStep> iconAnimator;

   public WsdlMockResponseTestStep( WsdlTestCase testCase, TestStepConfig config, boolean forLoadTest )
   {
      super( testCase, config, true, forLoadTest );

      if( config.getConfig() != null )
      {
         mockResponseStepConfig = (MockResponseStepConfig) config.getConfig().changeType( MockResponseStepConfig.type );
         mockResponseConfig = mockResponseStepConfig.getResponse();
      }
      else
      {
         mockResponseStepConfig = (MockResponseStepConfig) config.addNewConfig()
                 .changeType( MockResponseStepConfig.type );
         mockResponseConfig = mockResponseStepConfig.addNewResponse();
      }

      initAssertions();
      initMockObjects( testCase );

      if( !forLoadTest )
      {
         if( iface != null )
         {
            iface.getProject().addProjectListener( projectListener );
            iface.addInterfaceListener( interfaceListener );
         }

         iconAnimator = new ModelItemIconAnimator<WsdlMockResponseTestStep>( this, "/mockResponseStep.gif",
                 "/exec_mockResponse", 4, "gif" );
      }

      // init properties
      initProperties();

   }

   private void initProperties()
   {
      if( mockResponse != null )
      {
         addProperty( new TestStepBeanProperty( "Response", false, mockResponse, "responseContent", this ) );
      }

      addProperty( new DefaultTestStepProperty( "Request", true, new DefaultTestStepProperty.PropertyHandlerAdapter()
      {

         public String getValue( DefaultTestStepProperty property )
         {
            WsdlMockResult mockResult = mockResponse == null ? null : mockResponse.getMockResult();
            return mockResult == null ? null : mockResult.getMockRequest().getRequestContent();
         }
      }, this ) );
   }

   @Override
   public ImageIcon getIcon()
   {
      return iconAnimator == null ? null : iconAnimator.getIcon();
   }

   private void initAssertions()
   {
      assertionsSupport = new AssertionsSupport( this, new AssertableConfig()
      {

         public TestAssertionConfig addNewAssertion()
         {
            return mockResponseStepConfig.addNewAssertion();
         }

         public List<TestAssertionConfig> getAssertionList()
         {
            return mockResponseStepConfig.getAssertionList();
         }

         public void removeAssertion( int ix )
         {
            mockResponseStepConfig.removeAssertion( ix );
         }
      } );
   }

   private void initMockObjects( WsdlTestCase testCase )
   {
      MockServiceConfig mockServiceConfig = MockServiceConfig.Factory.newInstance();
      mockServiceConfig.setPath( mockResponseStepConfig.getPath() );
      mockServiceConfig.setPort( mockResponseStepConfig.getPort() );

      mockService = new WsdlTestMockService( this, mockServiceConfig );
      mockService.setName( getName() );

      iface = (WsdlInterface) testCase.getTestSuite().getProject().getInterfaceByName(
              mockResponseStepConfig.getInterface() );
      if( iface == null )
      {
      }
      else
      {
         iface.addInterfaceListener( interfaceListener );

         mockOperation = mockService.addNewMockOperation( iface
                 .getOperationByName( mockResponseStepConfig.getOperation() ) );

         if( mockResponseStepConfig.getHandleFault())
            mockService.setFaultMockOperation( mockOperation );

         if( mockResponseStepConfig.getHandleResponse())
            mockService.setDispatchResponseMessages( true );
         
         mockOperation.addNewMockResponse( "MockResponse", false );

         mockResponse = mockOperation.getMockResponseAt( 0 );
         mockResponse.setConfig( mockResponseConfig );

         mockResponse.addPropertyChangeListener( this );
      }
   }

   public void resetConfigOnMove( TestStepConfig config )
   {
      super.resetConfigOnMove( config );

      mockResponseStepConfig = (MockResponseStepConfig) config.getConfig().changeType( MockResponseStepConfig.type );
      mockResponseConfig = mockResponseStepConfig.getResponse();
      mockResponse.setConfig( mockResponseConfig );
      assertionsSupport.refresh();
   }

   @Override
   public boolean cancel()
   {
      if( mockRunner != null )
      {
         mockRunner.stop();
         mockRunner = null;
      }

      if( listener != null )
         listener.cancel();

      return true;
   }

   public TestStepResult run( TestRunner testRunner, TestRunContext context )
   {
      LoadTestRunner loadTestRunner = (LoadTestRunner) context.getProperty( TestRunContext.LOAD_TEST_RUNNER );
      if( loadTestRunner == null )
      {
         return internalRun( (WsdlTestRunContext) context );
      }
      else
      {
         // block other threads during loadtesting -> this should be improved!
         synchronized( STATUS_PROPERTY )
         {
            if( loadTestRunner.getStatus() == LoadTestRunner.Status.RUNNING )
               return internalRun( (WsdlTestRunContext) context );
            else
            {
               WsdlSingleMessageExchangeTestStepResult result = new WsdlSingleMessageExchangeTestStepResult( this );
               result.setStatus( TestStepStatus.UNKNOWN );
               return result;
            }
         }
      }
   }

   private TestStepResult internalRun( WsdlTestRunContext context )
   {
      iconAnimator.start();
      WsdlSingleMessageExchangeTestStepResult result = new WsdlSingleMessageExchangeTestStepResult( this );

      try
      {
         listener = new InternalMockRunListener();
         mockService.addMockRunListener( listener );

         mockRunner = mockService.start( context );
         result.startTimer();

         long timeout = getTimeout();
         synchronized( listener )
         {
            listener.wait( timeout );
         }

         if( mockRunner != null )
         {
            mockRunner.stop();
            mockRunner = null;
         }

         AssertedWsdlMockResultMessageExchange messageExchange = new AssertedWsdlMockResultMessageExchange( listener
                 .getResult() );
         result.setMessageExchange( messageExchange );

         if( listener.getResult() != null )
         {
            context.setProperty( AssertedXPathsContainer.ASSERTEDXPATHSCONTAINER_PROPERTY, messageExchange );
            assertResult( listener.getResult(), context );
         }

         result.stopTimer();

         if( listener.getResult() == null )
         {
            if( listener.isCanceled() )
            {
               result.setStatus( TestStepStatus.CANCELED );
            }
            else
            {
               result.setStatus( TestStepStatus.FAILED );
               result.addMessage( "Timeout occured after " + timeout + " milliseconds" );
            }
         }
         else
         {
            AssertionStatus status = getAssertionStatus();
            if( status == AssertionStatus.FAILED )
            {
               result.setStatus( TestStepStatus.FAILED );

               if( getAssertionCount() == 0 )
               {
                  result.addMessage( "Invalid/empty request" );
               }
               else
                  for( int c = 0; c < getAssertionCount(); c++ )
                  {
                     AssertionError[] errors = getAssertionAt( c ).getErrors();
                     if( errors != null )
                     {
                        for( AssertionError error : errors )
                        {
                           result.addMessage( error.getMessage() );
                        }
                     }
                  }
            }
            else
            {
               result.setStatus( TestStepStatus.OK );
            }
         }
      }
      catch( Exception e )
      {
         result.stopTimer();
         result.setStatus( TestStepStatus.FAILED );
         result.setError( e );
         SoapUI.logError( e );
      }
      finally
      {
         mockService.removeMockRunListener( listener );
         iconAnimator.stop();
      }

      return result;
   }

   private void assertResult( WsdlMockResult result, SubmitContext context )
   {
      if( oldStatus == null )
         oldStatus = getAssertionStatus();

      for( int c = 0; c < getAssertionCount(); c++ )
      {
         WsdlMessageAssertion assertion = getAssertionAt( c );
         if( !assertion.isDisabled() )
         {
            assertion.assertRequest( new WsdlMockResultMessageExchange( result, getMockResponse() ), context );
         }
      }

      AssertionStatus newStatus = getAssertionStatus();
      if( newStatus != oldStatus )
      {
         notifyPropertyChanged( STATUS_PROPERTY, oldStatus, newStatus );
         oldStatus = newStatus;
      }
   }

   @Override
   public void finish( TestRunner testRunner, TestRunContext testRunContext )
   {
      mockRunner = null;
   }

   public class InternalMockRunListener extends MockRunListenerAdapter
   {
      private WsdlMockResult result;
      private boolean canceled;

      public void onMockResult( MockResult result )
      {
         this.setResult( (WsdlMockResult) result );

         synchronized( this )
         {
            notifyAll();
         }
      }

      public void cancel()
      {
         canceled = true;
         listener.onMockResult( null );
      }

      private void setResult( WsdlMockResult result )
      {
         this.result = result;
      }

      public WsdlMockResult getResult()
      {
         return result;
      }

      public boolean isCanceled()
      {
         return canceled;
      }
   }

   public WsdlMockResponse getMockResponse()
   {
      return mockResponse;
   }

   public void setPort( int port )
   {
      mockService.setPort( port );
      mockResponseStepConfig.setPort( port );
   }

   public String getPath()
   {
      return mockResponseStepConfig.getPath();
   }

   public long getContentLength()
   {
      return mockResponse == null ? 0 : mockResponse.getContentLength();
   }

   public int getPort()
   {
      return mockResponseStepConfig.getPort();
   }

   public String getEncoding()
   {
      return mockResponse.getEncoding();
   }

   public void setEncoding( String encoding )
   {
      mockResponse.setEncoding( encoding );
   }

   public boolean isMtomEnabled()
   {
      return mockResponse.isMtomEnabled();
   }

   public void setMtomEnabled( boolean enabled )
   {
      mockResponse.setMtomEnabled( enabled );
   }

   public String getOutgoingWss()
   {
      return mockResponse.getOutgoingWss();
   }

   public void setOutgoingWss( String outgoingWss )
   {
      mockResponse.setOutgoingWss( outgoingWss );
   }

   public boolean isForceMtom()
   {
      return mockResponse.isForceMtom();
   }

   public void setForceMtom( boolean forceMtom )
   {
      mockResponse.setForceMtom( forceMtom );
   }

   public boolean isInlineFilesEnabled()
   {
      return mockResponse.isInlineFilesEnabled();
   }

   public void setInlineFilesEnabled( boolean inlineFilesEnabled )
   {
      mockResponse.setInlineFilesEnabled( inlineFilesEnabled );
   }

   public boolean isMultipartEnabled()
   {
      return mockResponse.isMultipartEnabled();
   }

   public void setMultipartEnabled( boolean enabled )
   {
      mockResponse.setMultipartEnabled( enabled );
   }

   public boolean isHandleFault()
   {
      return mockResponseStepConfig.getHandleFault();
   }

   public void setHandleFault( boolean handleFault )
   {
      mockResponseStepConfig.setHandleFault( handleFault );
      if( mockService != null )
         mockService.setFaultMockOperation( handleFault ? mockOperation : null );
   }

   public boolean isHandleResponse()
   {
      return mockResponseStepConfig.getHandleResponse();
   }

   public void setHandleResponse( boolean handleResponse )
   {
      mockResponseStepConfig.setHandleResponse( handleResponse );
      if( mockService != null )
         mockService.setDispatchResponseMessages( handleResponse );
   }

   public long getResponseDelay()
   {
      return mockResponse.getResponseDelay();
   }

   public void setResponseDelay( long delay )
   {
      mockResponse.setResponseDelay( delay );
   }

   public String getResponseHttpStatus()
   {
      return mockResponse.getResponseHttpStatus();
   }

   public void setResponseHttpStatus( String httpStatus )
   {
      mockResponse.setResponseHttpStatus( httpStatus );
   }

   public boolean isEncodeAttachments()
   {
      return mockResponse.isEncodeAttachments();
   }

   public boolean isRemoveEmptyContent()
   {
      return mockResponse.isRemoveEmptyContent();
   }

   public boolean isStripWhitespaces()
   {
      return mockResponse.isStripWhitespaces();
   }

   public void setEncodeAttachments( boolean encodeAttachments )
   {
      mockResponse.setEncodeAttachments( encodeAttachments );
   }

   public void setRemoveEmptyContent( boolean removeEmptyContent )
   {
      mockResponse.setRemoveEmptyContent( removeEmptyContent );
   }

   public void setStripWhitespaces( boolean stripWhitespaces )
   {
      mockResponse.setStripWhitespaces( stripWhitespaces );
   }

   public void setPath( String path )
   {
      mockService.setPath( path );
      mockResponseStepConfig.setPath( path );
   }

   public void propertyChange( PropertyChangeEvent evt )
   {
      if( evt.getSource() == mockResponse )
      {
         mockResponseConfig.set( mockResponse.getConfig() );
         notifyPropertyChanged( evt.getPropertyName(), evt.getOldValue(), evt.getNewValue() );
      }
   }

   public WsdlMessageAssertion addAssertion( String assertionName )
   {
      PropertyChangeNotifier notifier = new PropertyChangeNotifier();

      try
      {
         TestAssertionConfig assertionConfig = mockResponseStepConfig.addNewAssertion();
         assertionConfig.setType( TestAssertionRegistry.getInstance().getAssertionTypeForName( assertionName ) );

         WsdlMessageAssertion assertion = assertionsSupport.addWsdlAssertion( assertionConfig );
         assertionsSupport.fireAssertionAdded( assertion );

         if( getMockResponse().getMockResult() != null )
         {
            assertion.assertRequest( new WsdlMockResultMessageExchange( getMockResponse().getMockResult(),
                    getMockResponse() ), new WsdlSubmitContext( this ) );
            notifier.notifyChange();
         }

         return assertion;
      }
      catch( Exception e )
      {
         SoapUI.logError( e );
         return null;
      }
   }

   public void addAssertionsListener( AssertionsListener listener )
   {
      assertionsSupport.addAssertionsListener( listener );
   }

   public WsdlMessageAssertion getAssertionAt( int c )
   {
      return assertionsSupport.getAssertionAt( c );
   }

   public int getAssertionCount()
   {
      return assertionsSupport.getAssertionCount();
   }

   public void removeAssertionsListener( AssertionsListener listener )
   {
      assertionsSupport.removeAssertionsListener( listener );
   }

   public AssertionStatus getAssertionStatus()
   {
      AssertionStatus currentStatus = AssertionStatus.UNKNOWN;
      int cnt = getAssertionCount();
      if( cnt == 0 )
         return currentStatus;

      for( int c = 0; c < cnt; c++ )
      {
         WsdlMessageAssertion assertion = getAssertionAt( c );
         if( assertion.isDisabled() )
            continue;

         if( assertion.getStatus() == AssertionStatus.FAILED )
         {
            currentStatus = AssertionStatus.FAILED;
            break;
         }
      }

      if( currentStatus == AssertionStatus.UNKNOWN )
         currentStatus = AssertionStatus.VALID;

      return currentStatus;
   }

   public void removeAssertion( TestAssertion assertion )
   {
      PropertyChangeNotifier notifier = new PropertyChangeNotifier();

      try
      {
         assertionsSupport.removeAssertion( (WsdlMessageAssertion) assertion );
      }
      finally
      {
         ( (WsdlMessageAssertion) assertion ).release();
         notifier.notifyChange();
      }
   }

   public String getAssertableContent()
   {
      WsdlMockResult mockResult = getMockResponse().getMockResult();
      return mockResult == null ? null : mockResult.getMockRequest().getRequestContent();
   }

   public TestStep getTestStep()
   {
      return this;
   }

   @Override
   public void setName( String name )
   {
      super.setName( name );
      if( mockService != null )
         mockService.setName( getName() );
   }

   public WsdlInterface getInterface()
   {
      return getOperation().getInterface();
   }

   public WsdlOperation getOperation()
   {
      return getMockResponse().getMockOperation().getOperation();
   }

   public void setInterface( String string )
   {
      WsdlInterface iface = (WsdlInterface) getTestCase().getTestSuite().getProject().getInterfaceByName( string );
      if( iface != null )
      {
         mockResponseStepConfig.setInterface( iface.getName() );
         WsdlOperation operation = iface.getOperationAt( 0 );
         mockResponseStepConfig.setOperation( operation.getName() );
         mockOperation.setOperation( operation );
      }
   }

   public void setOperation( String string )
   {
      WsdlOperation operation = getInterface().getOperationByName( string );
      if( operation != null )
      {
         mockResponseStepConfig.setOperation( string );
         mockOperation.setOperation( operation );
      }
   }

   private class PropertyChangeNotifier
   {
      private AssertionStatus oldStatus;
      private ImageIcon oldIcon;

      public PropertyChangeNotifier()
      {
         oldStatus = getAssertionStatus();
         oldIcon = getIcon();
      }

      public void notifyChange()
      {
         AssertionStatus newStatus = getAssertionStatus();
         ImageIcon newIcon = getIcon();

         if( oldStatus != newStatus )
            notifyPropertyChanged( STATUS_PROPERTY, oldStatus, newStatus );

         if( oldIcon != newIcon )
            notifyPropertyChanged( ICON_PROPERTY, oldIcon, getIcon() );
      }
   }

   @Override
   public void release()
   {
      super.release();
      assertionsSupport.release();

      if( mockResponse != null )
         mockResponse.removePropertyChangeListener( this );

      if( mockService != null )
         mockService.release();

      if( iface != null )
      {
         iface.getProject().removeProjectListener( projectListener );
         iface.removeInterfaceListener( interfaceListener );
      }
   }

   public AssertableType getAssertableType()
   {
      return AssertableType.REQUEST;
   }

   @Override
   public Collection<WsdlInterface> getRequiredInterfaces()
   {
      ArrayList<WsdlInterface> result = new ArrayList<WsdlInterface>();
      result.add( getInterface() );
      return result;
   }

   public String getDefaultSourcePropertyName()
   {
      return "Response";
   }

   public String getDefaultTargetPropertyName()
   {
      return "Request";
   }

   @Override
   public void beforeSave()
   {
      super.beforeSave();

      if( mockResponse != null )
      {
         mockResponse.beforeSave();
         mockResponseConfig.set( mockResponse.getConfig() );
      }
   }

   public long getTimeout()
   {
      return mockResponseStepConfig.getTimeout();
   }

   public void setTimeout( long timeout )
   {
      long old = getTimeout();
      mockResponseStepConfig.setTimeout( timeout );
      notifyPropertyChanged( TIMEOUT_PROPERTY, old, timeout );
   }

   @Override
   public boolean dependsOn( AbstractWsdlModelItem<?> modelItem )
   {
      return modelItem == getOperation().getInterface();
   }

   public class InternalProjectListener extends ProjectListenerAdapter
   {
      public void interfaceRemoved( Interface iface )
      {
         if( getOperation() != null && getOperation().getInterface().equals( iface ) )
         {
            log.debug( "Removing test step due to removed interface" );
            ( getTestCase() ).removeTestStep( WsdlMockResponseTestStep.this );
         }
      }
   }

   public class InternalInterfaceListener extends InterfaceListenerAdapter
   {
      public void operationRemoved( Operation operation )
      {
         if( operation == getOperation() )
         {
            log.debug( "Removing test step due to removed operation" );
            ( getTestCase() ).removeTestStep( WsdlMockResponseTestStep.this );
         }
      }

      @Override
      public void operationUpdated( Operation operation )
      {
         if( operation == getOperation() )
         {
            setOperation( operation.getName() );
         }
      }
   }

   public WsdlMessageAssertion cloneAssertion( TestAssertion source, String name )
   {
      TestAssertionConfig conf = mockResponseStepConfig.addNewAssertion();
      conf.set( ( (WsdlMessageAssertion) source ).getConfig() );
      conf.setName( name );

      WsdlMessageAssertion result = assertionsSupport.addWsdlAssertion( conf );
      assertionsSupport.fireAssertionAdded( result );
      return result;
   }

   public List<TestAssertion> getAssertionList()
   {
      return new ArrayList<TestAssertion>( assertionsSupport.getAssertionList() );
   }

   @Override
   public List<? extends ModelItem> getChildren()
   {
      return assertionsSupport.getAssertionList();
   }

   public PropertyExpansion[] getPropertyExpansions()
   {
      List<PropertyExpansion> result = new ArrayList<PropertyExpansion>();

      result.addAll( PropertyExpansionUtils.extractPropertyExpansions( this, mockResponse, "responseContent" ) );

      StringToStringMap responseHeaders = mockResponse.getResponseHeaders();
      for( String key : responseHeaders.keySet() )
      {
         result.addAll( PropertyExpansionUtils.extractPropertyExpansions( this, new ResponseHeaderHolder( responseHeaders,
                 key ), "value" ) );
      }

      return result.toArray( new PropertyExpansion[result.size()] );
   }

   public class ResponseHeaderHolder
   {
      private final StringToStringMap valueMap;
      private final String key;

      public ResponseHeaderHolder( StringToStringMap valueMap, String key )
      {
         this.valueMap = valueMap;
         this.key = key;
      }

      public String getValue()
      {
         return valueMap.get( key );
      }

      public void setValue( String value )
      {
         valueMap.put( key, value );
         mockResponse.setResponseHeaders( valueMap );
      }
   }

   public WsdlMessageAssertion getAssertionByName( String name )
   {
      return assertionsSupport.getAssertionByName( name );
   }

   public Map<String, TestAssertion> getAssertions()
   {
      Map<String, TestAssertion> result = new HashMap<String, TestAssertion>();

      for( TestAssertion assertion : getAssertionList() )
         result.put( assertion.getName(), assertion );

      return result;
   }

   private class AssertedWsdlMockResultMessageExchange extends WsdlMockResultMessageExchange implements
           RequestAssertedMessageExchange, AssertedXPathsContainer
   {
      private List<AssertedXPath> assertedXPaths;

      public AssertedWsdlMockResultMessageExchange( WsdlMockResult mockResult )
      {
         super( mockResult, mockResult == null ? null : mockResult.getMockResponse() );
      }

      public AssertedXPath[] getAssertedXPathsForRequest()
      {
         return assertedXPaths == null ? new AssertedXPath[0] : assertedXPaths.toArray( new AssertedXPath[assertedXPaths
                 .size()] );
      }

      public void addAssertedXPath( AssertedXPath assertedXPath )
      {
         if( assertedXPaths == null )
            assertedXPaths = new ArrayList<AssertedXPath>();

         assertedXPaths.add( assertedXPath );
      }
   }

   public String getDefaultAssertableContent()
   {
      return getOperation().createRequest( true );
   }

   @SuppressWarnings( "unchecked" )
   @Override
   public void resolve( ResolveContext context )
   {
      super.resolve( context );

      if( mockOperation == null )
      {
         context.addPathToResolve( this, "Missing Operation in Project",
                 mockResponseStepConfig.getInterface() + "/" + mockResponseStepConfig.getOperation(),
                 new RemoveTestStepDefaultResolveAction() ).addResolvers( new ImportInterfaceResolver( this )
         {

            @Override
            protected boolean update()
            {
               initMockObjects( getTestCase() );
               initProperties();
               setDisabled( false );
               return true;
            }
         }, new ChangeOperationResolver( this )
         {

            @Override
            public boolean update()
            {
               WsdlOperation operation = (WsdlOperation) getPickedOperation();
               setInterface( operation.getInterface().getName() );
               setOperation( operation.getName() );
               initMockObjects( getTestCase() );
               initProperties();
               setDisabled( false );
               return true;
            }

         } );
      }
      else
      {
         mockOperation.resolve( context );
      }
   }

}