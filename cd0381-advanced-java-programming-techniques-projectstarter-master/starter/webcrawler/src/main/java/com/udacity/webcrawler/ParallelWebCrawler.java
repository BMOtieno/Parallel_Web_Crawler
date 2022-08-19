package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final List<Pattern> ignoredUrls;
  private final int maxDepth;
  private final PageParserFactory pageParserFactory;

  @Inject
  ParallelWebCrawler(
      Clock clock,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount,
      @IgnoredUrls List<Pattern> ignoredUrls,
      @MaxDepth int maxDepth,
      PageParserFactory pageParserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.ignoredUrls = ignoredUrls;
    this.maxDepth = maxDepth;
    this.pageParserFactory = pageParserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {

    Instant deadline = clock.instant().plus(timeout);
    ConcurrentHashMap<String, Integer> wordCounts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> urlsVisited = new ConcurrentSkipListSet<>();

    for (String inputUrl : startingUrls) {
      pool.invoke(new CrawlInternal(inputUrl, deadline, maxDepth, wordCounts, urlsVisited));
    }

    if (wordCounts.isEmpty()) {
      return new CrawlResult.Builder().setWordCounts(wordCounts).setUrlsVisited(urlsVisited.size()).build();
    }

    return new CrawlResult.Builder().setWordCounts(WordCounts.sort(wordCounts, popularWordCount)).setUrlsVisited(urlsVisited.size()).build();
  }
  public class CrawlInternal extends RecursiveTask<Boolean> {
    private String urlVisited;
    private Instant deadline;
    private int maxDepth;
    private ConcurrentHashMap<String, Integer> wordCounts;
    private ConcurrentSkipListSet<String> urlsVisited;

    public CrawlInternal(String urlVisited, Instant deadline, int maxDepth, ConcurrentHashMap<String, Integer> wordCounts, ConcurrentSkipListSet<String> urlsVisited) {
      this.urlVisited = urlVisited;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.wordCounts = wordCounts;
      this.urlsVisited = urlsVisited;
    }

    @Override
    protected Boolean compute() {
      if(maxDepth == 0 || clock.instant().isAfter(deadline)){
        return false;
      }
      for(Pattern pattern: ignoredUrls){
        if(pattern.matcher(urlVisited).matches()){
          return false;
        }
      }
      if(!urlsVisited.add(urlVisited)){
        return false;
      }

      PageParser.Result resultOfTheParser = pageParserFactory.get(urlVisited).parse();

      for(Map.Entry<String, Integer> myVariable : resultOfTheParser.getWordCounts().entrySet()){
        wordCounts.compute(myVariable.getKey(), (key, value) -> (value == null) ? myVariable.getValue(): myVariable.getValue() + value);
      }

      ArrayList<CrawlInternal> alternateTasks = new ArrayList<>();
      for(String url: resultOfTheParser.getLinks()){
        alternateTasks.add(new CrawlInternal(url, deadline, maxDepth -1, wordCounts, urlsVisited));
      }
      invokeAll(alternateTasks);
      return true;
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
