package com.javapipeline.core.search;

import com.javapipeline.core.CancellationToken;
import com.javapipeline.core.ProgressListener;

public interface GitHubRepositorySearchService {
    GitHubSearchResponse search(
            GitHubSearchCriteria criteria,
            char[] accessToken,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws GitHubSearchException;
}
