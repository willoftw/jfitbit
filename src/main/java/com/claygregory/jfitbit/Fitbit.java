package com.claygregory.jfitbit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.claygregory.common.data.Duration;
import com.claygregory.common.data.TimestampedEvent;
import com.claygregory.common.net.URLBuilder;
import com.claygregory.common.util.DateUtil;

/**
 * 
 * Fitbit data provider.
 * 
 * Note: Fitbit does not provide timezone information in data aggregates,
 * so it's assumed the timezone preference set in the Fitbit user profile matches timezone
 * settings in local environment.
 * 
 * @author Clay Gregory
 *
 */
public class Fitbit {
	
	private static String LOGIN_URL = "https://www.fitbit.com/login";
	
	private static String GRAPH_BASE_URL = "http://www.fitbit.com/graph/getGraphData";
	
	private static String SLEEP_BASE_URL = "http://www.fitbit.com/sleep/";
	
	private static DateFormat TWELVE_HOUR_RESULT_TIME_FORMAT = new SimpleDateFormat( "hh:mmaa", Locale.US );
	
	private static DateFormat TWENTY_FOUR_HOUR_RESULT_TIME_FORMAT = new SimpleDateFormat( "HH:mm", Locale.US );
	
	private static DateFormat RESULT_DATE_FORMAT = new SimpleDateFormat( "EEE, MMM dd", Locale.US );
	
	private static DateFormat URL_DATE_FORMAT = new SimpleDateFormat( "yyyy/MM/dd", Locale.US );
	
	private static DateFormat REQUEST_DATE_FORMAT = new SimpleDateFormat( "yyyy-M-dd", Locale.US );
	
	protected static class ResponseValue {
		
		protected String description;
		
		protected String value;
		
		protected Long startTimestamp;
		
		protected Long endTimestamp;
		
		protected Duration duration;

	}
	
	protected static abstract class ResponseHandler {
		
		protected abstract void process( ResponseValue value, boolean first );

	}
	
	static {
		TWELVE_HOUR_RESULT_TIME_FORMAT.setTimeZone( TimeZone.getTimeZone( "GMT" ) );
		TWENTY_FOUR_HOUR_RESULT_TIME_FORMAT.setTimeZone( TimeZone.getTimeZone( "GMT" ) );
	}
	
	private HttpClient httpClient;
	
	private String userId;

	public String getUserId()
	{
		return userId;
	}
	/**
	 * 
	 * @param email address on Fitbit account
	 * @param password of Fitbit account
	 * @throws FitbitAuthenticationException if authentication fails
	 */
	public Fitbit( String email, String password ) throws FitbitAuthenticationException {
		this.httpClient = createHttpClient( );
		this.userId = this.authenticate( email, password );
	}
	
	/**
	 * Provides activity level breakdown throughout the day. Only 
	 * available at {@link FitbitResolution#DAILY} resolution.
	 *  
	 * @param q
	 * @return List of {@link ActivityLevel}s for time range specified by query parameter
	 */
	public List<ActivityLevel> activityLevel( FitbitQuery q ) {
		
		String type = "";
		switch ( q.getResolution( ) ) {
			case INTRADAY:
				throw new IllegalArgumentException( );
			case DAILY:
				type = "minutesActive";
				break;
		}
		
		final List<ActivityLevel> result = new ArrayList<ActivityLevel>( );
		this.execute( type, q, new ResponseHandler( ) {
			
			protected ActivityLevel level;
			
			@Override
			protected void process( ResponseValue value, boolean first ) {
				
				if ( first ) {
					this.level = new ActivityLevel( );
					this.level.setTimestamp( value.startTimestamp );
					this.level.setIntervalSize( value.duration );
					
					result.add( this.level );
				}
				
				Duration d = new Duration( ( long ) Math.round( Float.parseFloat( value.value ) * Duration.MS_IN_HOURS ) );
				if ( value.description.contains( "lightly" ) )
					this.level.setLightlyActive( d );
				else if ( value.description.contains( "fairly" ) )
					this.level.setFairlyActive( d );
				else if ( value.description.contains( "very" ) )
					this.level.setVeryActive( d );
			}
		} );
		return filterResults( result, q );
	}
	
	/**
	 * Provides activity score breakdown at either daily or five-minute resolution.
	 *  
	 * @param q
	 * @return List of {@link ActivityScore}s for time range specified by query parameter
	 */
	public List<ActivityScore> activityScore( FitbitQuery q ) {
		
		String type = "";
		switch ( q.getResolution( ) ) {
			case INTRADAY:
				type = "intradayActiveScore";
				break;
			case DAILY:
				type = "activeScore";
				break;
		}
		
		final List<ActivityScore> result = new ArrayList<ActivityScore>( );
		this.execute( type, q, new ResponseHandler( ) {
			
			@Override
			protected void process( ResponseValue value, boolean first ) {
				ActivityScore as = new ActivityScore( );
				as.setScore( Math.round( Float.parseFloat( value.value ) ) );
				as.setTimestamp( value.startTimestamp );
				as.setIntervalSize( value.duration );
				result.add( as );
			}
		} );
		return filterResults( result, q );
	}
	
	/**
	 * Provides sleep level for each minute in bed. Only available at 
	 * {@link FitbitResolution#INTRADAY} resolution.
	 *  
	 * @param q
	 * @return List of {@link SleepLevel}s for time in bed during range specified by query parameter
	 */
	public List<SleepLevel> sleepLevel( FitbitQuery q ) {
		
		String type = "";
		switch ( q.getResolution( ) ) {
			case INTRADAY:
				type = "intradaySleep";
				break;
			case DAILY:
				throw new IllegalArgumentException( );
		}
		
		final List<SleepLevel> result = new ArrayList<SleepLevel>( );
		final List<SleepLevel> dayBuffer = new ArrayList<SleepLevel>( );
		this.executeSleep( type, q, new ResponseHandler( ) {
			
			@Override
			protected void process( ResponseValue value, boolean first ) {
				
				if ( first && !dayBuffer.isEmpty( ) ) {
					Date last = dayBuffer.get( dayBuffer.size( ) - 1 ).getTimestampAsDate( );
					for ( SleepLevel s : dayBuffer ) {
						Date real = s.getTimestampAsDate( );
						real = DateUtil.setDate( real, DateUtil.getDate( last ) );
						real = DateUtil.setMonth( real, DateUtil.getMonth( last ) );
						real = DateUtil.setYear( real, DateUtil.getYear( last ) );
						s.setTimestamp( real.getTime( ) );
					}
					result.addAll( dayBuffer );
					dayBuffer.clear( );
				}
				
				SleepLevel sl = new SleepLevel( );
				sl.setLevel( Math.round( Float.parseFloat( value.value ) ) );
				sl.setTimestamp( value.startTimestamp );
				sl.setIntervalSize( value.duration );
				result.add( sl );
			}
		} );
		
		if ( !dayBuffer.isEmpty( ) ) {
			Date last = dayBuffer.get( dayBuffer.size( ) - 1 ).getTimestampAsDate( );
			for ( SleepLevel s : dayBuffer ) {
				Date real = s.getTimestampAsDate( );
				real = DateUtil.setDate( real, DateUtil.getDate( last ) );
				real = DateUtil.setMonth( real, DateUtil.getMonth( last ) );
				real = DateUtil.setYear( real, DateUtil.getYear( last ) );
				s.setTimestamp( real.getTime( ) );
			}
			result.addAll( dayBuffer );
		}
		
		return filterResults( result, q );
	}
	
	/**
	 * Provides calories consumed throughout the day. Only 
	 * available at {@link FitbitResolution#DAILY} resolution.
	 * 
	 * @param q
	 * @return List of {@link CalorieCount}s for time range specified by query parameter
	 */
	public List<CalorieCount> calorieCount( FitbitQuery q ) {
		
		String type = "";
		switch ( q.getResolution( ) ) {
			case INTRADAY:
				throw new IllegalArgumentException( );
			case DAILY:
				type = "caloriesConsumed";
				break;
		}
		
		final List<CalorieCount> result = new ArrayList<CalorieCount>( );
		this.execute( type, q, new ResponseHandler( ) {
			
			@Override
			protected void process( ResponseValue value, boolean first ) {
				CalorieCount cc = new CalorieCount( );
				cc.setCalories( Math.round( Float.parseFloat( value.value ) ) );
				cc.setTimestamp( value.startTimestamp );
				cc.setIntervalSize( value.duration );
				result.add( cc );
			}
		} );
		return filterResults( result, q );
	}
	
	/**
	 * Provides step counts at either daily or five minute resolutions.
	 * 
	 * @param q
	 * @return List of {@link StepCount}s for time range specified by query parameter
	 */
	public List<StepCount> stepCount( FitbitQuery q ) {
		
		String type = "";
		switch ( q.getResolution( ) ) {
			case INTRADAY:
				type = "intradaySteps";
				break;
			case DAILY:
				type = "stepsTaken";
				break;
		}
		
		final List<StepCount> result = new ArrayList<StepCount>( );
		this.execute( type, q, new ResponseHandler( ) {
			
			@Override
			protected void process( ResponseValue value, boolean first ) {
				StepCount sc = new StepCount( );
				sc.setSteps( Math.round( Float.parseFloat( value.value ) ) );
				sc.setTimestamp( value.startTimestamp );
				sc.setIntervalSize( value.duration );
				result.add( sc );
			}
		} );
	
		return filterResults( result, q );
	}
	
	/**
	 * Provides floor counts at either daily or five minute resolutions.
	 * 
	 * @param q
	 * @return List of {@link FloorCount}s for time range specified by query parameter
	 */
	public List<FloorCount> floorCount( FitbitQuery q ) {
		
		String type = "";
		switch ( q.getResolution( ) ) {
			case INTRADAY:
				type = "intradayAltitude";
				break;
			case DAILY:
				type = "altitude";
				break;
		}
		
		final List<FloorCount> result = new ArrayList<FloorCount>( );
		this.execute( type, q, new ResponseHandler( ) {
			
			@Override
			protected void process( ResponseValue value, boolean first ) {
				FloorCount fc = new FloorCount( );
				fc.setFloors( Math.round( Float.parseFloat( value.value ) ) );
				fc.setTimestamp( value.startTimestamp );
				fc.setIntervalSize( value.duration );
				result.add( fc );
			}
		} );
		return filterResults( result, q );
	}

	private HttpClient createHttpClient( ) {
		DefaultHttpClient httpClient = new DefaultHttpClient( );

		httpClient.getParams( ).setParameter( ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY );
		httpClient.setRedirectStrategy( new DefaultRedirectStrategy( ) {
			
			@Override
			public boolean isRedirected( HttpRequest request, HttpResponse response, HttpContext context ) throws ProtocolException {
				int responseCode = response.getStatusLine( ).getStatusCode( );
				return super.isRedirected( request, response, context ) || responseCode == 301 || responseCode == 302;
			}
			
		} );

		return httpClient;
	}
	
	private String authenticate( String email, String password ) throws FitbitAuthenticationException {

		String response = null;
		try {
			EntityUtils.consume( this.httpClient.execute( new HttpGet( LOGIN_URL ) ).getEntity( ) );
			
			HttpPost loginPost = new HttpPost( LOGIN_URL );
			List<NameValuePair> parameters = new ArrayList<NameValuePair>( );
			parameters.add( new BasicNameValuePair( "email", email ) );
			parameters.add( new BasicNameValuePair( "password", password ) );
			parameters.add( new BasicNameValuePair( "login", "Log In" ) );
			UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity( parameters, "utf-8" );
			
			loginPost.setEntity( formEntity );
			
			response = EntityUtils.toString( this.httpClient.execute( loginPost ).getEntity( ) );
			
		} catch( Exception e ) {
			throw new FitbitExecutionException( e );
		}
		
		Matcher m = Pattern.compile( "=\"/user/([A-Z0-9]{6})\"" ).matcher( response );
		if ( !m.find( ) )
			throw new FitbitAuthenticationException( );
		
		return m.group( 1 );
	}
	
	private URL buildUrl( String type, Date date, Map<String,String> customParams ) throws MalformedURLException {
		URLBuilder builder = URLBuilder.create( GRAPH_BASE_URL );
		builder.queryParam( "userId", this.userId );
		
		builder.queryParam( "type", type );
		builder.queryParam( "version", "amchart" );
		builder.queryParam( "dataVersion", "14" );
		builder.queryParam( "chart_type", "column2d" );
		builder.queryParam( "period", "1d" );
		builder.queryParam( "dateTo", REQUEST_DATE_FORMAT.format( date ) );
		if ( customParams != null )
			for ( String key : customParams.keySet( ) )
				builder.queryParam( key, customParams.get( key ) );
		
		return builder.buildURL( );
	}
	
	private URL buildUrl( String type, Date date ) throws MalformedURLException {
		return this.buildUrl( type, date, null );
	}
	
	private void execute( String type, FitbitQuery query, ResponseHandler handler ) {
	
		for ( Date d : DateUtil.dateList( query.getMinimumTimestampAsDate( ), query.getMaximumTimestampAsDate( ), Calendar.DATE, 1, false ) ) {
			try {
				HttpGet get = new HttpGet( buildUrl( type, d ).toString( ) );
				String result = EntityUtils.toString( this.httpClient.execute( get ).getEntity( ) ).trim( );
				parseResult( d, result, handler );
			} catch( IOException e ) {
				throw new FitbitExecutionException( e );
			}
		}
	}
	
	//special handler for sleep -- must first fetch page to get IDs.
	private void executeSleep( String type, FitbitQuery query, ResponseHandler handler ) {
		
		for ( Date d : DateUtil.dateList( query.getMinimumTimestampAsDate( ), query.getMaximumTimestampAsDate( ) ) ) {
			try {
				HttpGet pageGet = new HttpGet( SLEEP_BASE_URL + URL_DATE_FORMAT.format( d ) );
				String pageResult = EntityUtils.toString( this.httpClient.execute( pageGet ).getEntity( ) );
				Matcher m = Pattern.compile( "sleepRecord\\.([0-9]+)" ).matcher( pageResult );
				while ( m.find( ) ) {
					String arg = m.group( 1 );
					Map<String,String> params = new HashMap<String,String>( );
					params.put( "arg", arg );
					HttpGet get = new HttpGet( buildUrl( type, d, params ).toString( ) );
					String result = EntityUtils.toString( this.httpClient.execute( get ).getEntity( ) ).trim( );
					parseResult( d, result, handler );
				}
			} catch( IOException e ) {
				throw new FitbitExecutionException( e );
			}
		}
	}
	
	private static<T extends TimestampedEvent> List<T> filterResults( List<T> results, FitbitQuery query ) {
		
		//since Fitbit returns paged results by day, no need to filter locally
		if ( query.getResolution( ) == FitbitResolution.DAILY )
			return results;
		
		List<T> filteredResults = new ArrayList<T>( );
		for ( T r : results )
			if ( r.getTimestamp( ) >= query.getMinimumTimestamp( ) && r.getTimestamp( ) <= query.getMaximumTimestamp( ) )
				filteredResults.add( r );
				
		return filteredResults;
	}
	
	private static long parseDate( Date requestedDate, String date ) throws ParseException {
		Date parsedDate = DateUtil.floor( RESULT_DATE_FORMAT.parse( date ), Calendar.DATE );
		return DateUtil.setYear( parsedDate, DateUtil.getYear( requestedDate ) ).getTime( );
	}
	
	private static long parseTime( Date requestedDate, String time ) throws ParseException {
		long timeMs;
		if ( time.toLowerCase( ).contains( "am" ) || time.toLowerCase( ).contains( "pm" ) )
			timeMs = TWELVE_HOUR_RESULT_TIME_FORMAT.parse( time ).getTime( );
		else
			timeMs = TWENTY_FOUR_HOUR_RESULT_TIME_FORMAT.parse( time ).getTime( );
				
		return timeMs + DateUtil.floor( requestedDate, Calendar.DATE ).getTime( );
	}
	
	private static void parseResult( Date requestedDate, String result, ResponseHandler handler ) {
		try {
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance( );
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder( );
			Document d = dBuilder.parse( new ByteArrayInputStream( result.getBytes( ) ) );
			
			XPath xpath = XPathFactory.newInstance( ).newXPath( );
			NodeList values = ( NodeList ) xpath.evaluate( "/settings/data/chart/graphs/graph/value", d, XPathConstants.NODESET );
			boolean first = true;
			for ( int i = 0; i < values.getLength( ); i++ ) {
				
				Node descriptionNode = values.item( i ).getAttributes( ).getNamedItem( "description" );
				String value = values.item( i ).getFirstChild( ).getNodeValue( );
				if ( descriptionNode != null ) {
					
					ResponseValue rValue = new ResponseValue( );
					rValue.description = descriptionNode.getTextContent( );
					rValue.value = value;
					
					if ( rValue.description.matches( ".* from .* to .*" ) ) {
						
						String[ ] descriptionParts = rValue.description.replaceFirst( ".* from ", "" ).split( " to " );
						rValue.startTimestamp = parseTime( requestedDate, descriptionParts[ 0 ] );
						rValue.endTimestamp = parseTime( requestedDate, descriptionParts[ 1 ] );
						
						rValue.duration = new Duration( rValue.endTimestamp - rValue.startTimestamp );
						if ( rValue.endTimestamp < rValue.startTimestamp )
							rValue.duration = rValue.duration.add( new Duration( 24, 0, 0 ) );
						
						handler.process( rValue, first );
						first = false;
						
					} else if ( rValue.description.matches( ".* on ..., ... \\d{1,2}" ) ) {
						
						rValue.startTimestamp = parseDate( requestedDate, rValue.description.replaceFirst( ".* on ", "" ) );
						rValue.duration = new Duration( 24, 0, 0 );
						
						handler.process( rValue, first );
						first = false;
						
					} else if ( rValue.description.matches( ".* at \\d{1,2}:\\d{2}.." ) ) {
						
						rValue.startTimestamp = parseTime( requestedDate, rValue.description.replaceFirst( ".* at ", "" ) );
						rValue.duration = new Duration( 1, 0 );
						
						handler.process( rValue, first );
						first = false;
					}
					
				}
			}

		} catch ( Exception e ) {
			throw new RuntimeException( e );
		}
	}
}
