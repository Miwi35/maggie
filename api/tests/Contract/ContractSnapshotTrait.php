<?php

namespace App\Tests\Contract;

/** @deprecated use {@see \PsychedLabs\Testing\Contract\ContractSnapshotTrait} */
trait ContractSnapshotTrait
{
    use \PsychedLabs\Testing\Contract\ContractSnapshotTrait;

    protected static function contractDir(): string
    {
        return \dirname(__DIR__, 2).'/contract';
    }
}
