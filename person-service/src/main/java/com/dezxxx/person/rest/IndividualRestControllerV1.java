package com.dezxxx.person.rest;

import com.dezxxx.person.api.IndividualsApi;
import com.dezxxx.person.api.model.CreateIndividualRequestDto;
import com.dezxxx.person.api.model.IndividualResponseDto;
import com.dezxxx.person.api.model.UpdateIndividualRequestDto;
import com.dezxxx.person.service.IndividualService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class IndividualRestControllerV1 implements IndividualsApi {

    private final IndividualService individualService;

    @Override
    public ResponseEntity<IndividualResponseDto> createIndividual(CreateIndividualRequestDto createIndividualRequestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(individualService.createIndividual(createIndividualRequestDto));
    }

    @Override
    public ResponseEntity<IndividualResponseDto> getIndividualById(UUID id) {
        return ResponseEntity.ok(individualService.getIndividualById(id));
    }

    @Override
    public ResponseEntity<IndividualResponseDto> getIndividualByEmail(String email) {
        return ResponseEntity.ok(individualService.getIndividualByEmail(email));
    }

    @Override
    public ResponseEntity<IndividualResponseDto> updateIndividual(UUID id, UpdateIndividualRequestDto updateIndividualRequestDto) {
        return ResponseEntity.ok(individualService.updateIndividual(id, updateIndividualRequestDto));
    }

    @Override
    public ResponseEntity<Void> deleteIndividual(UUID id) {
        individualService.deleteIndividual(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> compensateRegistration(UUID id) {
        individualService.compensateRegistration(id);
        return ResponseEntity.noContent().build();
    }
}
