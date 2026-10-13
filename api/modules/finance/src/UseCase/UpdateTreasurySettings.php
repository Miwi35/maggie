<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\TreasurySettings;

class UpdateTreasurySettings
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(TreasurySettings $settings): TreasurySettings
    {
        $this->em->flush();

        return $settings;
    }
}
