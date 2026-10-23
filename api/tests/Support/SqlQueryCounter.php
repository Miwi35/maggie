<?php

declare(strict_types=1);

namespace App\Tests\Support;

use Doctrine\DBAL\Driver;
use Doctrine\DBAL\Driver\Connection as DriverConnection;
use Doctrine\DBAL\Driver\Middleware;
use Doctrine\DBAL\Driver\Middleware\AbstractConnectionMiddleware;
use Doctrine\DBAL\Driver\Middleware\AbstractDriverMiddleware;
use Doctrine\DBAL\Driver\Result;
use Doctrine\DBAL\Driver\Statement;

/**
 * Counts the SQL statements the application sends, so a test can pin the cost
 * of a request independent of the size of its answer (MAG-544).
 */
final class SqlQueryCounter implements Middleware
{
    private int $count = 0;

    public function count(): int
    {
        return $this->count;
    }

    public function reset(): void
    {
        $this->count = 0;
    }

    public function increment(): void
    {
        ++$this->count;
    }

    public function wrap(Driver $driver): Driver
    {
        $counter = $this;

        return new class($driver, $counter) extends AbstractDriverMiddleware {
            public function __construct(Driver $driver, private readonly SqlQueryCounter $counter)
            {
                parent::__construct($driver);
            }

            public function connect(array $params): DriverConnection
            {
                return new class(parent::connect($params), $this->counter) extends AbstractConnectionMiddleware {
                    public function __construct(DriverConnection $connection, private readonly SqlQueryCounter $counter)
                    {
                        parent::__construct($connection);
                    }

                    public function prepare(string $sql): Statement
                    {
                        $this->counter->increment();

                        return parent::prepare($sql);
                    }

                    public function query(string $sql): Result
                    {
                        $this->counter->increment();

                        return parent::query($sql);
                    }

                    public function exec(string $sql): int|string
                    {
                        $this->counter->increment();

                        return parent::exec($sql);
                    }
                };
            }
        };
    }
}
