package scraper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.ContentHandler;

import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;

import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ling.CoreLabel;
public class Utilities {

	static final Pattern TAG_PERSON_REGEX = Pattern.compile("<PERSON>(.+?)</PERSON>");
	static final Pattern TAG_ORGANIZATION_REGEX = Pattern.compile("<ORGANIZATION>(.+?)</ORGANIZATION>");
	private static final String EMAIL_PATTERN = 
			"^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
			+ "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
	private static Set<String> conjunctions;
	private static Set<String> prepositions;
	private static Set<String> bannedother;
	private static Set<String> prefixes;
	private static Set<String> pronouns;
	private static Set<String> badtitlewords;
	private static Set<String> badfirstwords;
	private static Set<String> colleges;

	/**
	 * Create sets of words that are extremely unlikely to occur in people's names or in titles.
	 * The words must be single words (compounds are okay, but no whitespaces).
	 */
	public static void setBannedWords() {
		conjunctions = new HashSet<String>(Arrays.asList("and", "or", "but", "for", "yet"));
		prepositions = new HashSet<String>(Arrays.asList("aboard", "about", "across", "against", "amid", "anti", "as", "before", "below",
				"beside", "between", "but", "concerning", "despite", "during", "excepting", "following", "from",
				"inside", "like", "near", "off", "onto", "outside", "past", "plus", "round", "since", "through",
				"toward", "under", "unlike", "up", "versus", "with", "without"));
		bannedother = new HashSet<String>(Arrays.asList("a", "an", "colloquium", "seminar", "building", "lab", "group", "college", "auditorium", 
				"fellowship", "street", "home", "st.", "calendar", "room", "laboratory"));
		prefixes = new HashSet<String>(Arrays.asList("Ms", "Miss", "Mrs", "Mr", "Ms.", "Miss.", "Mrs.", "Mr.", "Professor"));
		pronouns = new HashSet<String>(Arrays.asList("he", "she", "I", "me", "her", "him"));
		badtitlewords = new HashSet<String>(Arrays.asList("please", "university", "visit", "professor", "director", "co-director", "discuss")); //should consist of mostly verbs too
		badfirstwords = new HashSet<String>(Arrays.asList("sponsor:", "sponsor", "speaker", "this", "speaker:"));
		colleges = new HashSet<String>(Arrays.asList("UC", "U.C.", "MIT", "Cornell", "Harvard", "Yale", "Mellon"));
	}

	/**
	 * Returns an array of html text that corresponds to the array of urls.
	 * 
	 * @param urls	An array of urls as an array of Strings
	 * @return	ArrayList<String> of html corresponding to the urls
	 */
	public static ArrayList<String> createfilecontents(String[] urls) {
		ArrayList<String> result = new ArrayList<String>();
		Document doc = null;
		for (int i = 0; i < urls.length; i++) {
			System.out.println("Connecting to " + urls[i]);
			try{
				if (!isPDF(urls[i])) {
					doc = Jsoup.connect(urls[i]).timeout(10000).get();
					result.add(doc.toString());
				} else {
					result.add(PDFtoString(processURL(urls[i])));
				}			
				System.out.println("Retrieved from " + urls[i]);
			} catch (Exception e ){
				System.out.println("Failed to connect " + urls[i]);
				result.add("");
			}

		}
		return result;
	}
	
	/**
	 * Determines whether a person's name satisfies a criteria or not.
	 * Edit this function to change the conditions.
	 * 
	 * @param person	A possible name for a person
	 * @return true if and only if person's name meets all conditions.
	 */
	public static boolean personconditions(String person){
		return !isAllUpper(person) && !isAllLower(person) 
				&& !person.matches(".*\\d.*") && !hasBannedWord(person);
	}
	
	
	/**
	 * Determines whether a candidate title satisfies a criteria or not.
	 * Edit this function to change the conditions.
	 * 
	 * @param content	A html-delimited entry (a candidate title)
	 * @return	True if the content satisfies the conditions given
	 */
	public static boolean hasBannedTitleWord(String content){
		String[] words = content.split("\\s+");
		for (int h = 0; h < words.length; h++) {
			if(pronouns.contains(words[h].toLowerCase())){
				return true;
			}
			if(badtitlewords.contains(words[h].toLowerCase())){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Removes the &nbsp; tags from the html entries and replace then with single whitespaces.
	 * 
	 * @param	entrycontents	entries associated to a talk
	 */
	public static void removenbsp(ArrayList<String> entrycontents){
		Iterator<String> i = entrycontents.iterator();
		while (i.hasNext()) {
		   String s = i.next();
		   if(s.trim().equals("&nbsp;")){
			   i.remove();			   
		   }
		}
		for(int j=0; j<entrycontents.size(); j++){
			entrycontents.set(j, entrycontents.get(j).replaceAll("&nbsp;", " ").trim());
		}
	}
	
	/**
	 * Extract the speaker names from the entries associated to a talk.
	 * Works best for contents with have a "speaker:" prepended.
	 * 
	 * @param entrycontents	entries associated to a talk
	 * @param classifier	A classifier from Stanford Named Entity Tagger
	 * @return
	 */
	public static ArrayList<String> getSpeakers(ArrayList<String> entrycontents, AbstractSequenceClassifier<CoreLabel> classifier){
		ArrayList<String> speakers = new ArrayList<String>();
		Set<String> tags = new HashSet<String>();
		String prevcontent = "";
		for (String content : entrycontents) {
			tags = getTagValues(classifier.classifyWithInlineXML(content), TAG_PERSON_REGEX);
			if(content.toLowerCase().contains("speaker:") && content.length() > 8){
				if(tags.size() > 0){
					speakers.addAll(tags);
				}
			}
			if(prevcontent.toLowerCase().trim().equals("speaker:")){
				if(tags.size() > 0){
					speakers.addAll(tags);
				}
			}
			prevcontent = content;
		}
		return speakers;
	}
	
	/**
	 * Shortens the list of potential positions of titles based a criteria.
	 * Edit this function to change the conditions. 
	 * 
	 * @param elems	An ArrayList of IndexElements (the frequency distribution where titles occur)
	 * @return a truncated ArrayList of IndexElements
	 */
	public static ArrayList<IndexElement> trimElems(ArrayList<IndexElement> elems){
		double sum = 0;
		for(IndexElement elem : elems){
			sum += elem.freq;
		}
		ArrayList<IndexElement> res = new ArrayList<IndexElement>();
		double threshold = 0.95*sum;
		double currsum = 0;
		int cnt = 0;
		for(IndexElement elem : elems){
			if(cnt < 3 || currsum <= threshold){
				res.add(elem);
				currsum += elem.freq;
			} else {
				return res;
			}
			cnt++;
		}
		return res;
	}

	/**
	 * Returns the most likely title of a talk given entries split by html tags.
	 * Takes into account for speaker names and frequency of entries as well.
	 * Edit this function to tweak the priorities for selecting titles.
	 * 
	 * @param	entrycontents	entries associated to a talk
	 * @param	speakers	list of speakers associated in this talk
	 * @param	elems		estimated frequencies of the index of a title (preprocessed list)
	 * @param	entryfreqs	actual frequencies of entries in the entire seminar
	 * @return	the most likely title
	 * */
	public static String getTitle(ArrayList<String> entrycontents, ArrayList<String> speakers, ArrayList<IndexElement> elems, Map<String, Integer> entryfreqs){
		String title = "";
		int len = 6;
		int currlen = 0;
		
		//currently, the loop condition is that "title" is the longest entry that follows all rules
		for (IndexElement elem : elems) {
			String content = (elem.index >= 0 && elem.index < entrycontents.size()) ? entrycontents.get(elem.index) : "";
			String prevcontent = (elem.index > 0 && elem.index < entrycontents.size()) ? entrycontents.get(elem.index-1) : "";
			
			//if title is TBA, TBD, etc..., automatically take it
			if(acceptablepending(prevcontent, content)){
				return content;
			}
			
			//if entry contains "title:", take text afterwards as title
			if(content.toLowerCase().contains("title:") && content.length() > 6){
				return content.substring(content.toLowerCase().indexOf("title:")+6).trim();
			}
			
			//if entry is equal to "title:", let the next entry be the title
			if(content.equalsIgnoreCase("title:")){
				return (elem.index + 1 < entrycontents.size()) ? entrycontents.get(elem.index+1) : "";
			}
			
			//if the previous entry is equal to "title:", the take the current entry
			if(prevcontent.toLowerCase().trim().equals("title:")){
				return content;
			}
			
			//if the previous entry contains "title", take text afterwards as title
			if(prevcontent.toLowerCase().contains("title:") && prevcontent.length() > 6){
				return prevcontent.substring(prevcontent.toLowerCase().indexOf("title:")+6).trim();
			}
			
			//assume that anything contained entirely in quotes is a title
			if(content.length() > currlen && content.charAt(0) == '"' && content.charAt(content.length()-1) == '"'){
				return content;
			}
			
			//edit canbetitle() in order to allow certain titles to be considered or excluded
			if(canbetitle(len, content, speakers) && (content.contains("TBD") ||  
					content.contains("TBA") || content.contains("TBC") || entryfreqs.get(content) < 2)) {
				String origcontent = content;
				if(content.length() > currlen){
					len = content.length();
					title = content;	
				}
				content = origcontent;
			}
			prevcontent = content;
		}
		return title;
	}
	
	/**
	 * Important: Solely for pre-processing only.
	 * Determines the most likely index of a title, so that the frequency distributions
	 * can be later used (as ArrayList<IndexElement>) to retrieve the actual titles.
	 * Edit this function to correspond with getTitle if applicable.
	 * 
	 * @param	entrycontents	entries associated to a talk
	 * @param	speakers	list of speakers associated in this talk
	 * @return the most likely index of a title
	 */
	public static int getTitleIndex(ArrayList<String> entrycontents, ArrayList<String> speakers) {																					// time
		int len = 6;
		int currlen = 0;
		String prevcontent = "";
		int i = 0; int title_i = 0;
		for (String content : entrycontents) {
			if(acceptablepending(prevcontent, content)){
				return i;
			}
			if(content.toLowerCase().contains("title:")){
				return i;
			}
			if(prevcontent.toLowerCase().trim().equals("title:")){
				return i;
			}
			if(content.length() > currlen && content.charAt(0) == '"' && content.charAt(content.length()-1) == '"'){
				return i;
			}
			if(canbetitle(len, content, speakers)) {
				String origcontent = content;
				if(content.length() > currlen){
					len = content.length();
					title_i = i;
				}
				content = origcontent;
			}
			prevcontent = content;
			i++;
		}
		return title_i;
	}
	
	/**
	 * Updates the actual frequencies of entries themselves (not indices of titles)
	 * 
	 * @param contents	entries associated to a talk
	 * @param entryfreqs	A Map where (key, value) := (entry, frequency)
	 */
	public static void updatetitlefreqs(ArrayList<String> contents, Map<String, Integer> entryfreqs){
		for(String content : contents){
			if(entryfreqs.containsKey(content)){
				entryfreqs.put(content, entryfreqs.get(content)+1);
			} else {
				entryfreqs.put(content, 0);
			}
		}
	}
	
	//Determines whether a candidate title (content) passes all of the criteria or not. 
	//Edit this function to change the conditions (useful for debugging as well).
	private static boolean canbetitle(int minlen, String content, ArrayList<String> speakers){
		/*try{ //for debugging only
			System.out.println(content.substring(0, Math.min(content.length(), 80)) + " " + (StringUtils.countMatches(content, ";") <= StringUtils.countMatches(content, "&nbsp;") + StringUtils.countMatches(content, "&amp;"))
					+ " " + !hastimedate(content) + " " + !hasemail(content) + " " + !content.contains("@") + " " + !content.contains("|") + " " + !hasBannedTitleWord(content) + " " + !hasbadfirstword(content)
					+ " " + !content.contains("\n") + " " + propercaps(content) + " " + !hasseminarlastword(content) + " " + (StringUtils.countMatches(content, ",") <= 3)
					+ " " + ((content.charAt(content.length()-1) != '.') || ((content.charAt(content.length()-1) == '.') && (StringUtils.countMatches(content, " ") < 15)))
					+ " " + !surroundedparens(content) + " " + !titleisspeaker(content, speakers) + " " + !seminarastitle(content) + " " + !content.trim().substring(0, 3).equals("of "));	
		} catch (Exception e){}*/
		return content.length() > minlen && content.length() < 200 && (StringUtils.countMatches(content, ";") <= StringUtils.countMatches(content, "&nbsp;") + StringUtils.countMatches(content, "&amp;"))
				&& !hastimedate(content) && !hasemail(content) && !content.contains("@") && !content.contains("|") && !hasBannedTitleWord(content) && !hasbadfirstword(content)
				&& !content.contains("\n") && propercaps(content) && !hasseminarlastword(content) && (StringUtils.countMatches(content, ",") <= 3) 
				&& (content.charAt(content.length()-1) != '.' || (content.charAt(content.length()-1) == '.' && StringUtils.countMatches(content, " ") < 15))
				&& !surroundedparens(content) && !titleisspeaker(content, speakers) && !seminarastitle(content) && !content.trim().substring(0, 3).equals("of ");
	}
	
	//essentially a list of unlikely last words
	private static boolean hasseminarlastword(String content){
		content = content.trim();
		if(content.equalsIgnoreCase("seminar") || content.equalsIgnoreCase("seminars") || content.equalsIgnoreCase("institute")){
			return true;
		}
		int last_i = content.lastIndexOf(" ");
		if(last_i >= 0){
			content = content.substring(last_i).trim();
			if(content.equalsIgnoreCase("seminar") || content.equalsIgnoreCase("seminars") || content.equalsIgnoreCase("institute")){
				return true;
			}
		}
		return false;
	}
	
	//the seminar need to have the first letter capitalized
	private static boolean propercaps(String content){
		int i = 0;
		while(content.substring(i).indexOf(" ") != -1 && 
				i<content.length() && !Character.isLetter(content.charAt(i))) {
			i = i + content.substring(i).indexOf(" ") + 1;
		}
		return (i<content.length() && Character.isUpperCase(content.charAt(i)));
	}

	//a list of unlikely first words
	private static boolean hasbadfirstword(String content){
		content = content.trim().toLowerCase();
		if(badfirstwords.contains(content)){
			return true;
		}
		int first_i = content.indexOf(" ");
		if(first_i >= 0){
			content = content.substring(0, first_i);
			if(badfirstwords.contains(content)){
				return true;
			}
		}
		return false;
	}
	
	//it is preferable to take the title if it is TBA, TBD, or anything similar
	private static boolean acceptablepending(String prevcontent, String content){
		content = content.toUpperCase().trim();
		return content.length() >= 3 && !prevcontent.toLowerCase().contains("location") && !prevcontent.toLowerCase().contains("speaker")
				&& (content.substring(0,3).equals("TBA") || content.substring(0,3).equals("TBD") || content.substring(0,3).equals("TBC"));
	}
	
	//if too much of the title is just the speaker's name, or if speaker's name come first, then its unlikely to be a title
	private static boolean titleisspeaker(String content, ArrayList<String> speakers){
		content = content.trim();
		int overlap = 0;
		for(String sp : speakers){
			if(content.indexOf(sp) == 0){
				return true;
			}
			if(content.equals(sp.trim())){
				return true;
			}
			if(content.contains(sp)){
				overlap = overlap + sp.length();
			}
		}
		return 2*overlap > content.length();
	}
	
	//titles are usually not surrounded by parenthesis
	private static boolean surroundedparens(String content){
		String tmp = content.trim();
		if(tmp.length() > 0){
			return tmp.charAt(0) == '(' && tmp.charAt(content.length()-1) == ')';
		}
		return false;
	}

	/**
	 * Prints out the ArrayList of Map<date, entrycontents> in a easier to read manner.
	 * 
	 * @param wholemap	The list of all of the information of each seminar split by date
	 */
	public static void betterprint(ArrayList<LinkedHashMap<Date, ArrayList<String>>> wholemap) {
		int i = 0;
		for (LinkedHashMap<Date, ArrayList<String>> linkedmap : wholemap) {
			System.out.println("Document " + (i++));
			Set<Date> keys = linkedmap.keySet();
			for (Date key : keys) {
				System.out.println(key + " = " + linkedmap.get(key));
			}
		}
	}

	/**
	 * Prints out all of the talks from all of the seminars from the provided urls.
	 * 
	 * @param allseminars	ArrayList of all talks from all seminars from the urls
	 * @param urls			landing pages for the seminars
	 */
	public static void printseminars(ArrayList<ArrayList<SeminarEntry>> allseminars, String[] urls) {
		int i = 0;
		SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd");
		for (ArrayList<SeminarEntry> seminars : allseminars) {
			System.out.println("From " + urls[(i++)]);
			for (SeminarEntry seminar : seminars) {
				System.out.println("Date: " + dateFormat.format(seminar.date));
				System.out.println("Speaker(s): " + seminar.speakers);
				System.out.println("Link(s): " + seminar.links);
				System.out.println("Title: " + seminar.title);
				System.out.println(" ");
			}
			System.out.println(" ");
		}
	}
	
	/**
	 * Returns a JSONArray of the talks from all of the sminars from the provided urls.
	 * 
	 * @param allseminars	ArrayList of all talks from all seminars from the urls
	 * @param urls			landing pages for the seminars
	 * @return
	 * @throws Exception 
	 */
	@SuppressWarnings("unchecked")
	public static JSONArray seminarsjson(ArrayList<ArrayList<SeminarEntry>> allseminars, String[] urls) throws Exception{
		int i=0;
		SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd");
		JSONArray allsemsjsonarr = new JSONArray();
		JSONObject semsjsonobj = new JSONObject();
		JSONArray semsjsonarr = new JSONArray();
		JSONObject semjsonobj = new JSONObject();
		JSONArray tmpjsonarr = new JSONArray();
		for (ArrayList<SeminarEntry> seminars : allseminars) {
			semsjsonobj.put("Webpage", urls[(i++)]);
			for(SeminarEntry seminar : seminars) {
				semjsonobj.put("date", dateFormat.format(seminar.date));
				
				for(String person : seminar.speakers){
					tmpjsonarr.add(person);
				}
				semjsonobj.put("speakers", tmpjsonarr);
				tmpjsonarr = new JSONArray();
				
				semjsonobj.put("title", seminar.title);
				
				for(String link : seminar.links){
					tmpjsonarr.add(link);
				}
				semjsonobj.put("links", tmpjsonarr);
				tmpjsonarr = new JSONArray();

				semsjsonarr.add(semjsonobj);				
				semjsonobj = new JSONObject();
			}
			allsemsjsonarr.add(semsjsonarr);
			semsjsonobj = new JSONObject();
			semsjsonarr = new JSONArray();
		}
		String filename = "seminars.json";
		File file = new File(filename);
		if (file.exists()) {
			removefile(filename);
		}
		createfile(filename);
		BufferedWriter writer = new BufferedWriter(new FileWriter(file));
		System.out.println("Writing to " + filename);

		writer.write(allsemsjsonarr.toJSONString());
		writer.close();		
		return allsemsjsonarr;
	}
	
	/**
	 * Outputs all of the talks from all of the seminars from given urls into a CSV file
	 * 
	 * @param allseminars	ArrayList of all talks from all seminars from the urls
	 * @param urls			landing pages for the seminars
	 * @throws Exception
	 */
	public static void writeseminarscsv(ArrayList<ArrayList<SeminarEntry>> allseminars, String[] urls) throws Exception{
		int i = 0;
		SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd");
		String filename = "seminars.csv";
		File file = new File(filename);
		if (file.exists()) {
			removefile(filename);
		}
		createfile(filename);
		BufferedWriter writer = new BufferedWriter(new FileWriter(file));
		System.out.println("Writing to " + filename);
		String webpage = "";
		writer.write("Landing page,");
		writer.write("Date,");
		writer.write("Speakers,");
		writer.write("Title,");
		writer.write("Links\n");
		for (ArrayList<SeminarEntry> seminars : allseminars) {
			webpage = urls[(i++)];
			for (SeminarEntry seminar : seminars) {
				writer.write("\"" + webpage + "\",");
				writer.write("\"" + dateFormat.format(seminar.date) + "\",");
				writer.write("\"" + seminar.speakers + "\",");
				writer.write("\"" + seminar.title + "\",");
				writer.write(links_for_csv(seminar.links) + "\n");
			}
			writer.write("\n");
		}
		writer.close();
	}
	
	//properly comma-separate the links of a seminar
	private static String links_for_csv(ArrayList<String> links){
		StringBuffer res = new StringBuffer("");
		for(String link : links){
			res.append("\"" + link + "\",");
		}
		String strres = res.toString();
		if(strres.length() > 2){
			return strres.substring(0, strres.length()-1);
		}			
		return strres;
	}
	
	/**
	 * Writes all of the talks from all of the seminars from given urls into a .txt file.
	 * 
	 * @param allseminars	ArrayList of all talks from all seminars from the urls
	 * @param urls			landing pages for the seminars
	 * @throws Exception
	 */
	public static void writeseminars(ArrayList<ArrayList<SeminarEntry>> allseminars, String[] urls) throws Exception{
		int i = 0;
		SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd/yy");
		String filename = "seminars.txt";
		File file = new File(filename);
		if (file.exists()) {
			removefile(filename);
		}
		createfile(filename);
		BufferedWriter writer = new BufferedWriter(new FileWriter(file));
		System.out.println("Writing to " + filename);
		for (ArrayList<SeminarEntry> seminars : allseminars) {
			writer.write("Webpage: " + urls[(i++)] + "\n");
			for (SeminarEntry seminar : seminars) {
				writer.write("Date: " + dateFormat.format(seminar.date) + "\n");
				writer.write("Speaker(s): " + seminar.speakers + "\n");
				writer.write("Link(s): " + seminar.links + "\n");
				writer.write("Title: " + seminar.title + "\n");
				writer.write("\n");
			}
			writer.write("\n");
		}
		writer.close();
	}

	/**
	 * Removes a person's name from all seminars in an ArrayList of seminars
	 * 
	 * @param seminars	ArrayList of seminars
	 * @param person	a person's name
	 */
	public static void remove_person(ArrayList<SeminarEntry> seminars, String person) {
		for(SeminarEntry seminar : seminars) {
			seminar.removeSpeaker(person);
		}
	}
	
	/**
	 * Removes a talk from an ArrayList of seminars based on a set of criteria
	 * Edit this function to change the conditions.
	 * 
	 * @param seminars	ArrayList of seminars
	 */
	public static void remove_invalid_talks(ArrayList<SeminarEntry> seminars){
		
		for(int i=0; i<seminars.size(); i++){
			if((seminars.get(i).title.trim().length() == 0) && links_as_email(seminars.get(i))){
				seminars.get(i).clearSpeakers();
			}
		}
		
		Iterator<SeminarEntry> sem_iter = seminars.iterator();
		while(sem_iter.hasNext()){
			SeminarEntry sem = sem_iter.next();
			if(sem.speakers.size() == 0){
				sem_iter.remove();				
			}
		}
	}
	
	//whether the seminar's links contain a email address or not
	private static boolean links_as_email(SeminarEntry seminarent){
		for(String link : seminarent.links){
			if(link.length() > 0 && link.substring(0,7).equals("mailto:")){
				if(emailvalidate(link.substring(7))){
					return true;
				}
			} else if (emailvalidate(link)){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Remove extraneous people from the last-entry to account for run-offs.
	 * Currently does not function as intended.
	 * 
	 * @param date_contents	an ArrayList of contents associated to a talk (equivalent to a date)
	 * @param seminarent	a single talk
	 */
	public static void remove_extraneous_people(ArrayList<String> date_contents, SeminarEntry seminarent){
		String title = seminarent.title;
		ArrayList<String> people = seminarent.speakers;
		ArrayList<String> newpeople = new ArrayList<String>();
		int title_i = 0;
		System.out.println(date_contents);
		for(String content : date_contents){
			if(content.length() > 6 && content.substring(0,6).equalsIgnoreCase("title:")){
				content = content.substring(0,6);
			}
			if(content.equals(title)){
				break;
			}
			title_i++;
		}
		for(int i=0; i<=title_i; i++){
			for(String person : people){
				if(date_contents.get(i).contains(person)){
					newpeople.add(person);
				}
			}
		}
		seminarent.speakers = newpeople;
	}
	
	//determines whether a url is a pdf or not
	private static boolean isPDF(String url) {
		String[] tmp = url.split("\\.");
		return tmp[tmp.length - 1].equals("pdf");
	}

	//attempts to retrieve the "html" tags of a url accounting for both real webpages and pdf files 
	private static String processURL(String url) throws Exception {
		String[] tmp = url.split("\\:");
		String tmpfilename = "_tmp";
		File tmpfile = new File(tmpfilename);
		if (tmpfile.exists()) {
			removefile(tmpfilename);
		}
		createfile(tmpfilename);
		if (tmp.length > 1) {
			if (tmp[0].equals("file")) {
				return url.substring(tmp[0].length() + 1);
			} else if (isPDF(url) && (tmp[0].equals("http") || tmp[0].equals("https"))) {
				FileUtils.copyURLToFile(new URL(url), tmpfile);
				return "_tmp";
			}
		}
		return url;
	}

	//creates a file with the given filename
	private static void createfile(String filename) throws Exception {
		File file = new File(filename);
		BufferedWriter writer = new BufferedWriter(new FileWriter(file));
		writer.write("");
		writer.close();
	}

	//removes the file with the given filename
	private static void removefile(String filename) {
		File tmpfile = new File(filename);
		tmpfile.delete();
	}

	//attempts to retrieve "html" text from pdf files, its not very html-like though
	private static String PDFtoString(String filename) throws IOException {
		InputStream is = null;
		try {
			is = new FileInputStream(filename);
			ContentHandler contenthandler = new ToHTMLContentHandler();
			Metadata metadata = new Metadata();
			PDFParser pdfparser = new PDFParser();
			pdfparser.parse(is, contenthandler, metadata, new ParseContext());
			return contenthandler.toString();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (is != null)
				is.close();
		}
		return "";
	}

	/**
	 * Splits the html text into an ArrayList of entries delimited by html tags.
	 * The actual html tags are also stripped away as well.
	 * 
	 * @param html	The html text of a webpage
	 * @return	ArrayList of entries split by html tags
	 */
	public static ArrayList<String> splitHTML(String html) {
		boolean outsidebrack = true;
		StringBuffer tmpstr = new StringBuffer("");
		ArrayList<String> result = new ArrayList<String>();
		for (int i = 0; i < html.length(); i++) {
			if (html.charAt(i) == '<') {
				outsidebrack = false;
			} else if (html.charAt(i) == '>') {
				outsidebrack = true;
			} else {
				if (outsidebrack) {
					tmpstr.append(html.charAt(i));
				} else {
					if (tmpstr.toString().trim().length() > 0)
						result.add(tmpstr.toString().trim());
					tmpstr = new StringBuffer("");
				}
			}
		}
		return result;
	}

	/**
	 * Retrieves all of the embedded links (such as speaker's homepage, talk page, etc...) 
	 * within an array of given webpages.
	 * 
	 * @param 	urls	The array of given webpages
	 * @return	a map of (key, value) := (link text, link href)
	 */
	public static ArrayList<LinkedHashMap<String, String>> getlinks(String[] urls) {
		ArrayList<LinkedHashMap<String, String>> result = new ArrayList<LinkedHashMap<String, String>>();
		for (int i = 0; i < urls.length; i++) {
			LinkedHashMap<String, String> tmp = new LinkedHashMap<String, String>();
			try{
				if (!isPDF(urls[i])) {
					Document doc = Jsoup.connect(urls[i]).timeout(10000).get();
					Elements links = doc.select("a[href]");
					for (Element link : links) {
						String linkHref = link.attr("abs:href");
						String linkText = link.text();
						tmp.put(linkText, linkHref);
					}

				}
				result.add(tmp);
			} catch (Exception e){
				System.out.println("Failed to fetch links for " + urls[i]);
				result.add(tmp);
			}
		}
		return result;
	}

	/**
	 * Extract the dates and times from an entry.
	 * 
	 * @param entry	An entry associated with a talk.
	 * @return ArrayList of Dates in that entry
	 */
	public static ArrayList<Date> parseDate(String entry) {
		return parseDate(entry, false);
	}

	/**
	 * Extract the dates from an entry. 
	 * If explicitOnly, then only the dates with actual a month and day are extracted. 
	 * 
	 * @param entry	An entry associated with a talk.
	 * @param explicitOnly	True if and only if dates with a month and day are wanted.
	 * @return	ArrayList of Dates in that entry
	 */
	public static ArrayList<Date> parseDate(String entry, boolean explicitOnly) {
		ArrayList<Date> res = new ArrayList<Date>();
		try{
			if (entry.length() < 80) {
				Parser parser = new Parser();
				java.util.List<DateGroup> groups = parser.parse(entry);
				for (DateGroup group : groups) {
					java.util.List<Date> dates = group.getDates();
					for (Date date : dates) {
						if (explicitOnly) {
							String treetext = group.getSyntaxTree().toStringTree();
							if (treetext.contains("EXPLICIT_DATE"))
								res.add(date);
						} else {
							res.add(date);
						}
					}
				}
			}
		} catch (Exception e){}
		return res;
	}

	/**
	 * Extract the Dates ("times") from an entry. 
	 * If needTime, then only the dates with an actual time of the day are extracted. 
	 * 
	 * @param entry	An entry associated with a talk.
	 * @param needTime	True if and only if dates with an actual time of the day are wanted.
	 * @return	ArrayList of Dates in that entry
	 */
	public static ArrayList<Date> parseTime(String str, boolean needTime) {
		Parser parser = new Parser();
		java.util.List<DateGroup> groups = parser.parse(str);
		ArrayList<Date> res = new ArrayList<Date>();
		for (DateGroup group : groups) {
			java.util.List<Date> dates = group.getDates();
			for (Date date : dates) {
				if (needTime) {
					String treetext = group.getSyntaxTree().toStringTree();
					if (treetext.contains("EXPLICIT_TIME"))
						res.add(date);
				} else {
					res.add(date);
				}
			}
		}
		return res;
	}

	// http://stackoverflow.com/questions/16523067/how-to-use-stanford-parser
	/**
	 * Extracts a set of tags generated by a classifier, based on the given regex g
	 * 
	 * @param str	result generated from a classifier
	 * @param regex	The desired pattern regex
	 * @return
	 */
	public static Set<String> getTagValues(String str, Pattern regex) {
		final Set<String> tagValues = new HashSet<String>();
		final Matcher matcher = regex.matcher(str);
		while (matcher.find()) {
			tagValues.add(matcher.group(1));
		}

		return tagValues;
	}

	//if all the characters in a string is lower case
	private static boolean isAllUpper(String s) {
		String[] tmps = s.split("\\s+");
		for (int i = 0; i < tmps.length; i++) {
			for (int j = 0; j < tmps[i].length(); j++) {
				if (Character.isLowerCase(tmps[i].charAt(j))) {
					return false;
				}
			}
		}
		return true;
	}

	//if all the characters in a stirng are upper case
	private static boolean isAllLower(String s) {
		String[] tmps = s.split("\\s+");
		for (int i = 0; i < tmps.length; i++) {
			for (int j = 0; j < tmps[i].length(); j++) {
				if (Character.isUpperCase(tmps[i].charAt(j))) {
					return false;
				}
			}
		}
		return true;
	}

	//where a person's name contains an undesirable word
	private static boolean hasBannedWord(String s) {
		String[] words = s.split("\\s+");
		for (int h = 0; h < words.length; h++) {
			if(conjunctions.contains(words[h].toLowerCase())){
				return true;
			}
			if(prepositions.contains(words[h].toLowerCase())){
				return true;
			}
			if(bannedother.contains(words[h].toLowerCase())){
				return true;
			}
			if(colleges.contains(words[h])){
				return true;
			}
		}
		return false;
	}

	//a person's name contains a valid prefix
	public static boolean isprefix(String word) {
		if(prefixes.contains(word)){
			return true;
		}
		return false;
	}

	/**
	 * Extract the word that occurs before a person's name in a String
	 * 
	 * @param content	the String entry associated in a talk
	 * @param person	a person's name
	 * @return	a word that occurs before that person's name
	 */
	public static String prevword(String content, String person) {
		int index = content.indexOf(person);
		int lastindex = index;
		boolean sawspace = false;
		while (index > 0) {
			index--;
			if (!sawspace) {
				sawspace = (content.charAt(index) == ' ');
			} else if (content.charAt(index) == ' ') {
				break;
			}
		}
		if (lastindex > 0) {
			return content.substring(index + 1, lastindex - 1);
		} else {
			return "";
		}
	}
	
	//whether the title contains a person's email address or not
	private static boolean hasemail(String title){
		String[] tmps = title.split("\\s+");
		for(int i=0; i<tmps.length; i++){
			if(emailvalidate(tmps[i])){
				return true;
			}
		}
		return false;
	}
	
	//whether the title contains an explicit time/date or not
	private static boolean hastimedate(String title){
		Parser parser = new Parser();
		try{
			java.util.List<DateGroup> groups = parser.parse(title);
			for(DateGroup group : groups) {
				String treetext = group.getSyntaxTree().toStringTree();
				if(treetext.contains("EXPLICIT")){
					return true;
				}
			}
		} catch(Exception e){}
		return false;
	}
	
	//determines whether the string is an person's email address or not
	private static boolean emailvalidate(final String hex) {
		Pattern pattern = Pattern.compile(EMAIL_PATTERN);
		Matcher matcher = pattern.matcher(hex);
		return matcher.matches();
	}
	
	//determines whether the "seminar" is a title or not
	private static boolean seminarastitle(String content){
		String[] words = content.split("\\s+");
		if(words.length == 1){
			return content.trim().toLowerCase().contains("seminar");
		} else if (words.length == 2){
			return words[0].trim().toLowerCase().contains("seminar") 
					|| words[1].trim().toLowerCase().contains("seminar");
		}
		return false;
	}
	
	/**
	 * Increments the element at a specified index regardless of range
	 * 
	 * @param lst	the desired ArrayList
	 * @param pos	index to insert the element
	 * @param x		element
	 */
	public static void safeincrement(ArrayList<Integer> lst, int pos){
		while(pos >= lst.size()){
			lst.add(0);
		}
		lst.set(pos, lst.get(pos)+1);
	}
}


