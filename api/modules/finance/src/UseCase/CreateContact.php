<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Contact;

class CreateContact
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Contact $contact): Contact
    {
        $this->em->persist($contact);
        $this->em->flush();

        return $contact;
    }
}
