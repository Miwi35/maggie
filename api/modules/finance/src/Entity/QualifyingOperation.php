<?php

declare(strict_types=1);

namespace Maggie\Finance\Entity;

/**
 * What qualifies transactions: a recurring operation or a categorization rule,
 * until part 3 merges them into one operation. Its qualification is copied
 * whole onto every transaction it recognises: `$transaction->qualify($operation->getQualification(), $operation)`.
 */
interface QualifyingOperation
{
    /** The whole qualification, its category included. */
    public function getQualification(): Qualification;
}
