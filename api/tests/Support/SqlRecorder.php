<?php

declare(strict_types=1);

namespace App\Tests\Support;

use Doctrine\DBAL\Driver;
use Doctrine\DBAL\Driver\Connection;
use Doctrine\DBAL\Driver\Middleware;
use Doctrine\DBAL\Driver\Middleware\AbstractConnectionMiddleware;
use Doctrine\DBAL\Driver\Middleware\AbstractDriverMiddleware;
use Doctrine\DBAL\Driver\Result;
use Doctrine\DBAL\Driver\Statement;

/**
 * Every statement the database connection is asked to run, in order: lets a
 * test pin how many a request costs and that none of them writes (MAG-545).
 * Wired as a DBAL middleware in the test environment only; the log is static
 * because the kernel may be booted more than once in a test.
 */
final class SqlRecorder implements Middleware
{
    /** @var list<string> */
    private static array $statements = [];

    public function wrap(Driver $driver): Driver
    {
        $recorder = $this;

        return new class($driver, $recorder) extends AbstractDriverMiddleware {
            public function __construct(Driver $driver, private readonly SqlRecorder $recorder)
            {
                parent::__construct($driver);
            }

            public function connect(array $params): Connection
            {
                return new class(parent::connect($params), $this->recorder) extends AbstractConnectionMiddleware {
                    public function __construct(Connection $connection, private readonly SqlRecorder $recorder)
                    {
                        parent::__construct($connection);
                    }

                    public function prepare(string $sql): Statement
                    {
                        $this->recorder->record($sql);

                        return parent::prepare($sql);
                    }

                    public function query(string $sql): Result
                    {
                        $this->recorder->record($sql);

                        return parent::query($sql);
                    }

                    public function exec(string $sql): int|string
                    {
                        $this->recorder->record($sql);

                        return parent::exec($sql);
                    }
                };
            }
        };
    }

    public function record(string $sql): void
    {
        self::$statements[] = $sql;
    }

    public static function reset(): void
    {
        self::$statements = [];
    }

    /** @return list<string> */
    public static function statements(): array
    {
        return self::$statements;
    }
}
