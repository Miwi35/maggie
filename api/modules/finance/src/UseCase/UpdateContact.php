<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Contact;

class UpdateContact
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Contact $contact): Contact
    {
        $this->em->flush();

        return $contact;
    }
}
