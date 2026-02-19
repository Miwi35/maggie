<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Elastic\Elasticsearch\Client;
use Elastic\Elasticsearch\ClientBuilder;

final class ElasticsearchClientFactory
{
    public static function create(string $elasticsearchUrl): Client
    {
        return ClientBuilder::create()
            ->setHosts([$elasticsearchUrl])
            ->build();
    }
}
