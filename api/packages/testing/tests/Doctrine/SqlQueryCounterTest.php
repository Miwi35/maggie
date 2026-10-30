<?php

declare(strict_types=1);

namespace PsychedLabs\Testing\Tests\Doctrine;

use Doctrine\DBAL\Configuration;
use Doctrine\DBAL\DriverManager;
use PHPUnit\Framework\TestCase;
use PsychedLabs\Testing\Doctrine\SqlQueryCounter;

final class SqlQueryCounterTest extends TestCase
{
    public function testItCountsEveryStatementSentToTheDriver(): void
    {
        $counter = new SqlQueryCounter();
        $connection = DriverManager::getConnection(
            ['driver' => 'pdo_sqlite', 'memory' => true],
            (new Configuration())->setMiddlewares([$counter]),
        );

        $connection->executeStatement('CREATE TABLE t (id INTEGER)');
        $connection->executeStatement('INSERT INTO t VALUES (?)', [1]);
        $connection->fetchAllAssociative('SELECT * FROM t');

        self::assertSame(3, $counter->count());

        $counter->reset();

        self::assertSame(0, $counter->count());
    }
}
