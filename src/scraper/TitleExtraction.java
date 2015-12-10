package scraper;

import java.util.Date;
import java.util.Scanner;

import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;

public class TitleExtraction {
	
	static double threshold;
	static int longestword;
	//based on average complexity of a non-disjunct and non-preposition word
	//the higher complexity the more likely it is a title
	//complexity is based on much word is used in english
		//otherwise u might need to train that data...
	//cannot contain embedded numbers in words
	//cannot contain email links
	//cannot contain phone numbers
	//cannot contain the speaker's name
	//assume it will not contain an explicit date or explicit time
	public static void main(){
		threshold = 0.8;
		longestword = 30;
		test();
	}
	
	public static void test(){
		//take input of title then its truth value
		
		//create more fields with it and store it as an object
		//then put the stuff into a arff file
		
		//then open the arff file
		
		//
		//run naivebayes
		
	}
	
	public static void generatetrainingfile(){
		Scanner input = new Scanner(System.in);
		
		//length
		//contains date or time
		//contains person's name
	}
	
	public static void generatetestfile(){
		
	}
		
	public static double probtitle(String title){
		return 0;
	}
	
	public static boolean okwordlength(String title){
		String[] tmps = title.split("\\s+");
		for(int i=0; i<tmps.length; i++){
			if(tmps[0].length() > longestword){
				return false;
			}
		}
		return true;
	}
	
	public static boolean istitle(String title){
		return probtitle(title) >= threshold;
	}
	

	
	//another alternative is just to record frequencies of parse tags from stanford
	//train the data based on those frequencies
}
