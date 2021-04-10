/*  clock-pro.c 
 *
 *  Clock-pro is to simulate the LIRS algorithm, where replacement actions 
 *  are taken only when there is a page fault. On a hit, only reference bit is set.
 *  
 *                                        Author: Song Jiang 9/29/05
 */


/* Input File: 
 *              trace file(.trc)
 *              parameter file(.par)
 *
 * Output File: 
 *              hit rate file(.cuv): it describes the hit rates for each 
 *              cache size specified in parameter file. Gnuplot can use it 
 *              to draw hit rate curve. 
 *
 *              cold allocation file (.sln): It records the percentage
 *              of memory for cold pages for a specific cache size like 
 *              Fig.3 in the paper. Be noted that only the results for the
 *              last cache size are recorded in the file if multiple cache 
 *              sizes are specified in the parameter file.  
 *
 *              cold allocation file (.ref): It records refault number for 
 *              a window of accesses in the trace. Be noted that only the 
 *              results for the last cache size are recorded in the file 
 *              if multiple cache sizes are specified in the parameter file.  
 */


/* Input File Format: 
 * (1) trace file: the (UBN) Unique Block Number of each reference, which
 *     is the unique number for each accessed block. It is strongly recommended
 *     that all blocks are mapped into 0 ... N-1 (or 1 ... N) if the total  
 *     access blocks is N. For example, if the accessed block numbers are:
 *     52312, 13456, 52312, 13456, 72345, then N = 3, and what appears in the 
 *     trace file is 0 1 0 1 2 (or 1 2 1 2 3). You can write a program using 
 *     hash table to do the trace conversion, or modify the program. 
 * (2) parameter file: 
 *      one or more cache sizes you want to use in the test 
 *     
 */

/* Command Line Uasge: only prefix of trace file is required. e.g.
   ./clock-pro my-trace
   It is implied that trace file is "my-trace.trc", parameter file is "my-trace.par"
   output files are "my-trace.cuv", "my-trace.sln" and "my-trace.ref"
*/

/* BE NOTED: If you want to customize the clock-pro algorithm, go to lirs.h 
 *           to change parameters.
 */

#include "clock-pro.h"

/* main function for the clock-pro algorithm */
void run_clock_pro(FILE *, FILE *, FILE *);

/* functions handling clock hands */
int run_HAND_hot(page_struct *);
void run_HAND_cold();
void run_HAND_test(int);

/* some helper functions */
FILE *openReadFile();
void init_one_page(page_struct *);
void remove_from_clock(page_struct *, int);
     void insert_to_clock_head(page_struct *);
     void check_adaptive_bound();
/* get the range of accessed blocks [1:N] and the number of references */ 
int get_range(FILE *, long *, long *);


/* for debug only */
void stat_check();
void view_clock(long);


int main()
//int main(int argc, char* argv[])
{
  FILE *trace_fp, *cuv_fp, *sln_fp, *para_fp, *refault_fp;
  unsigned long i;
  char trc_file_name[100]; 
  char para_file_name[100];
  char cuv_file_name[100];
  char sln_file_name[100];
  char refault_file_name[100];

  int argc; 
  char argv[10][1000];

	argc = 2;
	strcpy(argv[1], "loop");

  if (argc != 2){
    printf("%s file_name_prefix[.trc] \n", argv[0]);
    return 1;
  }
  
  strcpy(para_file_name, argv[1]);
  strcat(para_file_name, ".par");
  para_fp = openReadFile(para_file_name);

  strcpy(trc_file_name, argv[1]);
  strcat(trc_file_name, ".trc");
  trace_fp = openReadFile(trc_file_name);

  strcpy(cuv_file_name, argv[1]);
  strcpy(sln_file_name, argv[1]);
  strcpy(refault_file_name, argv[1]); 
  
  strcat(cuv_file_name, ".cuv");
  strcat(sln_file_name, ".sln");
  strcat(refault_file_name, ".ref");

  cuv_fp = fopen(cuv_file_name, "w");
  
  /* obtain the size of total reference space and trace length */ 
  if (!get_range(trace_fp, &vm_size, &total_pg_refs)){
    printf("trace error!\n");
    return 1;
  }
  
  fscanf(para_fp, "%ld", &mem_size);

  page_tbl = (page_struct *)calloc(vm_size+1, sizeof(page_struct));

  /* for each specified memory size, run clock-pro. */ 
  while (!feof(para_fp) && mem_size > 0){
    if (mem_size < 5){
      printf("WARNING: Too small cache size(%ld), stop. \n", mem_size);
      break;
    }

    sln_fp = fopen(sln_file_name, "w");
    
#ifdef REFAULTS_STAT
    refault_fp = fopen(refault_file_name, "w");
#endif

    total_pg_refs = 0;
    warm_pg_refs = 0;
    no_dup_refs = 0;
    num_pg_flt = 0;

    clock_size = 0;
    num_hot_pages = 0;
    num_non_res_pages = 0;
    ColdIncreaseCredits = 0;

    fseek(trace_fp, 0, SEEK_SET);

    free_mem_size = mem_size;

    /* initialize the page table */
    for (i = 0; i <= vm_size; i++){
      page_tbl[i].ref_times = 0;
      page_tbl[i].pf_times = 0; 

      /* unset the ref_bit */
      page_tbl[i].ref_bit = 0; 
      
      page_tbl[i].page_num = i;

      init_one_page((page_struct *)&page_tbl[i]);
    }

    clock_head = NULL;
    hand_hot = NULL;
    hand_cold = NULL;
    hand_test = NULL;
    

    /* verify the band in which cold allocation adapts */
    MIN_cold_alloc = (unsigned long)(MIN_COLD_ALLOC_RATE/100.0*mem_size);
    if (MIN_cold_alloc < LOWEST_COLD_ALLOC)
			MIN_cold_alloc = LOWEST_COLD_ALLOC;

    if (MAX_COLD_ALLOC_RATE < MIN_COLD_ALLOC_RATE)
			MAX_cold_alloc = MIN_cold_alloc;	
    else {
			MAX_cold_alloc = (unsigned long)(MAX_COLD_ALLOC_RATE/100.0*mem_size);
			if (MAX_cold_alloc < LOWEST_COLD_ALLOC)
	    MAX_cold_alloc = LOWEST_COLD_ALLOC;
    }
    
    printf("mem_size = %ld cold_alloc = [%d, %d]\n", mem_size, MIN_cold_alloc, MAX_cold_alloc);

    ColdIncreaseCredits = 0;

    /* run clock-pro here ! */
    run_clock_pro(trace_fp, sln_fp, refault_fp);
   
    printf("total blocks refs = %ld  number of misses = %ld ==> hit rate = %2.1f\%\n",  total_pg_refs, num_pg_flt, (1-(float)num_pg_flt/warm_pg_refs)*100);

    fprintf(cuv_fp, "%5ld  %2.1f\n", mem_size, 100-(float)num_pg_flt/warm_pg_refs*100);

    if (sln_fp)
      fclose(sln_fp);

    /* go on with the next memory size */
    fscanf(para_fp, "%ld", &mem_size);
  }

  return 0;
}
  


void run_clock_pro(FILE *trace_fp, FILE *sln_fp, FILE *refault_fp)
{
  unsigned long ref_page, i;
  long last_ref_pg = -1;
  int collect_stat = (STAT_START_POINT==0)?1:0;
  int is_matched;
  page_struct *ref_page_p;

#ifdef REFAULTS_STAT
  long num_redefaults = 0;
#endif

  fseek(trace_fp, 0, SEEK_SET);  

  fscanf(trace_fp, "%lu", &ref_page);

  i = 0;

  while (!feof(trace_fp)){
    total_pg_refs++;

    /* for debug */
    /*if (total_pg_refs % 10000 == 0)
      fprintf(stderr, "%ld samples processed\r", total_pg_refs);
    */

    if (total_pg_refs > STAT_START_POINT){
      collect_stat = 1;
      warm_pg_refs++;
    }
      
    if (ref_page > vm_size){
      printf("Wrong ref page number found: %ld.\n", ref_page);
      return;
    }
    
    if (ref_page == last_ref_pg){
      fscanf(trace_fp, "%lu", &ref_page);
      continue;
    }
    else
      last_ref_pg = ref_page;
    
    no_dup_refs++; /* ref counter excluding duplicate refs */

    ref_page_p = (page_struct *)&page_tbl[ref_page];

    view_clock(ref_page);
    


    /**************************************************************/
    /************************** BLOCK HIT *************************/
    /**************************************************************/
    /* hit in the cache ==> just set the ref_bit */
    if (ref_page_p->is_resident) {  /* block hit */
      ref_page_p->ref_bit = 1;
      goto next_ref_no_ref_reset;
    }



    /**************************************************************/
    /************************** BLOCK  MISS ***********************/
    /**************************************************************/    
    /* page_tbl[ref_page].is_resident == FALSE ==> block miss */
    
    /* page miss */
    if (collect_stat == 1)
	num_pg_flt++;

    /************ initilization *******************/
    /* fill the clock with (mem_size - MIN_cold_alloc) hot pages at first,
     * then MIN_cold_alloc cold pages.
     */

    /* a default hot page */ 
    if (free_mem_size > MIN_cold_alloc){
	ref_page_p->is_resident = TRUE;
	free_mem_size--;

	ref_page_p->is_cold_page = FALSE;
	num_hot_pages++;


	/* first page in the clock */
	if (!hand_hot) {
	  hand_hot = ref_page_p;
	  hand_test = hand_hot;
	  hand_hot->next = hand_hot;
	  hand_hot->prev = hand_hot;	  
	  clock_head = hand_hot;

	  ref_page_p->is_in_clock = TRUE;
	  clock_size++;
	}
	else 
	  insert_to_clock_head(ref_page_p);
	  
	goto next_ref;
    }

    /* a default resident cold page */
    else if (free_mem_size > 0) {
      ref_page_p->is_resident = TRUE;
      free_mem_size--;

      ref_page_p->is_in_test = TRUE;


      /* first cold page in the clock */
      if (!hand_cold) 
	hand_cold = ref_page_p;
      
      insert_to_clock_head(ref_page_p);

      goto next_ref;
    }

#ifdef DEBUG
    assert(page_tbl[ref_page].is_cold_page == TRUE);
#endif    

    /**************** for out of clock cold page *****************/
  out_of_clock:
    
    if (!ref_page_p->is_in_clock){

      /* if a page is originally identified as in-clock and 
       * ends up being out-of-clock by run_HAND_hot,
       * its free_mem_size is 1.
       */

      if (free_mem_size == 0)
	run_HAND_cold();
      assert(free_mem_size == 1);
      
      ref_page_p->is_resident = TRUE;
      free_mem_size--;

      insert_to_clock_head(ref_page_p);
      ref_page_p->is_in_test = TRUE;
    }

    /**************** for in clock non-resident cold page *****************/
    else {
#ifdef DEBUG
      assert(ref_page_p->is_in_test);
#endif

      /* get a free page */
      run_HAND_cold();
#ifdef DEBUG
      assert(free_mem_size == 1);
#endif
      
      if (!ref_page_p->is_in_clock)
	goto out_of_clock;

#ifdef DEBUG
      assert(ref_page_p->is_in_test);
#endif
      
      is_matched = run_HAND_hot(ref_page_p);

      if (is_matched == 1)
	goto out_of_clock;

      /* be promoted */
      ref_page_p->is_resident = TRUE;
      free_mem_size--;

      ref_page_p->is_cold_page = FALSE;
      num_hot_pages++;

#ifdef ADAPTIVE_COLD_ALLOC        
      ColdIncreaseCredits++;
#endif

#ifdef REFAULTS_STAT  
      num_redefaults++;
#endif

      if (is_matched == 2) 
	assert(!ref_page_p->next);
      else {
	if (ref_page_p == hand_test)
	  hand_test = hand_test->prev;
	
	remove_from_clock(ref_page_p, 0);
	num_non_res_pages--;
      }

      insert_to_clock_head(ref_page_p);
    }
	    
    next_ref:
      page_tbl[ref_page].ref_bit = 0;    

    next_ref_no_ref_reset:
    /*  To reduce the *.sln file size, ratios of stack size are 
     *  recorded every 2 references */        
    if ( total_pg_refs%2 ==0 && sln_fp)
	    fprintf(sln_fp, "%4d %f\n", total_pg_refs,  100.0*num_cold_pages/mem_size);

    /* you might be interested in checking these statistcs : 
      fprintf(sln_fp, "%4u %2.2f\n", total_pg_refs, (float)clock_size/mem_size);      
      fprintf(sln_fp, "%4d %d\n", total_pg_refs,   ColdIncreaseCredits);
      fprintf(sln_fp, "%4d %d\n", total_pg_refs,  num_cold_pages);
    */

#ifdef REFAULTS_STAT 
    if (no_dup_refs % NUM_REFAULT_WINDOW == 0) {
      fprintf(refault_fp, "%4d %d\n", no_dup_refs, num_redefaults);
      num_redefaults = 0;
    }
#endif

    fscanf(trace_fp, "%lu", &ref_page);
  }

  return;
}

 
/* this function searches for a hot page with its bit of 0 to replace.  
 * return value: 
 *           2: HAND_hot meets 'cold_ptr' and a hot page is demoted into a cold one
 *              
 *           1: Otherwise, HAND_hot meets 'cold_ptr'; 
 *           0: Otherwise.
 */
int run_HAND_hot(page_struct * cold_ptr)
{
  int is_done = FALSE;
  int is_matched = FALSE;
  int is_demote = FALSE;
  page_struct * tmp_pg;


  /* 'cold_ptr' is the cold page that triggers the movement of HAND_hot */
#ifdef DEBUG
  assert(cold_ptr->is_cold_page == TRUE && cold_ptr->is_in_clock == TRUE);
  assert(cold_ptr->is_in_test);
#endif
  
  while (!is_done || hand_hot->is_cold_page) {
    
    /* see a hot page */
    if (!hand_hot->is_cold_page) {
      
      if (hand_hot->ref_bit) 
	  hand_hot->ref_bit = 0;
      else {
	hand_hot->is_cold_page = 1;
	is_done = TRUE;
	num_hot_pages--;
	is_demote = TRUE;
      }
      
      tmp_pg = hand_hot;
      if (hand_test == hand_hot)
	hand_test = hand_test->prev;
      hand_hot = hand_hot->prev;

      remove_from_clock(tmp_pg, 0);
      insert_to_clock_head(tmp_pg);
    }
    /* see a cold page */
    else {
      if (hand_hot == cold_ptr) {
	  is_done = TRUE;
	  is_matched = TRUE;
	  assert(hand_hot == hand_test);
      }
      
      hand_hot = hand_hot->prev;
    }
    if (hand_hot->next == hand_test)
      run_HAND_test(1);
  }

  if (is_matched && is_demote)
    return 2;
  else if (is_matched)
    return 1;
  else
    return 0;
}
  

/* 
 *  Once HANDhot tries passing HANDtest, HANDhot == HANDtest 
 */ 

void run_HAND_test(int must_move_one_step)
{
  if (!must_move_one_step)
    goto non_res_limit_enforce;

  if (hand_test->is_cold_page) {

    if (!hand_test->is_resident){
      assert(hand_test->is_in_test);
#ifdef ADAPTIVE_COLD_ALLOC
      ColdIncreaseCredits--;
#endif
    }

    hand_test->is_in_test = FALSE;
    hand_test->ref_bit = 0;

    if (!hand_test->is_resident) {
      hand_test = hand_test->prev;
      remove_from_clock(hand_test->next, 1);
      num_non_res_pages--;
      assert(num_non_res_pages <= MAX_NONE_RES_PAGE);
    }
    else
      hand_test = hand_test->prev;
  }
  else
    hand_test = hand_test->prev;

 non_res_limit_enforce:
  if (num_non_res_pages > MAX_NONE_RES_PAGE) {
    while (hand_test->is_resident) {
      if (hand_test->is_cold_page) {
	hand_test->is_in_test = FALSE;
	hand_test->ref_bit = 0;
      }
      hand_test = hand_test->prev;
    }

#ifdef ADAPTIVE_COLD_ALLOC
    ColdIncreaseCredits--;     
#endif
    hand_test = hand_test->prev;
    remove_from_clock(hand_test->next, 1);
    num_non_res_pages--;
    assert(num_non_res_pages <= MAX_NONE_RES_PAGE);
  }

  return;
}

void run_HAND_cold()
{
  page_struct * temp_pg;
  int is_matched;

    assert(hand_cold->is_cold_page && hand_cold->is_resident);
    
    /* non-accessed page */
 evictable_page:
    if (!hand_cold->ref_bit || !hand_cold->is_in_test) {
      hand_cold->is_resident = 0;
      free_mem_size++;
      hand_cold = hand_cold->prev;

      /* in_test --> stay in the clock */
      if (hand_cold->next->is_in_test) 
	num_non_res_pages++;
      else
	remove_from_clock(hand_cold->next, 1);

      while (!hand_cold->is_cold_page) 
	hand_cold = hand_cold->prev;

      assert(hand_cold->is_resident);

      /* to limit the non_res_cold_pages */
      run_HAND_test(0);
	
      return; 
    }

    /* accessed page */
    else {
      /* filter the ColdIncreaseCredits */
      check_adaptive_bound();

      /* promote the cold page w/o running HAND_hot */
      if (ColdIncreaseCredits < 0) {
	temp_pg = hand_cold;
	hand_cold = hand_cold->prev;
	remove_from_clock(temp_pg, 0);
	temp_pg->is_cold_page = FALSE;
	num_hot_pages++;
	temp_pg->ref_bit = 0;
	insert_to_clock_head(temp_pg);
	ColdIncreaseCredits++;
      }


      /* demote a hot page w/o promoting the cold page 
       * to compensate the accessed page, move it to top w/o resetting its bit 
       */
      else if (ColdIncreaseCredits > 0) {	
	is_matched = run_HAND_hot(hand_cold); 
	if (!is_matched) {
	  temp_pg = hand_cold;
	  hand_cold = hand_cold->prev;
	  remove_from_clock(temp_pg, 0);
	  hand_cold->ref_bit = 1; /* due to resetting at remove_from_clock */
	  temp_pg->is_in_test = TRUE;
	  insert_to_clock_head(temp_pg);
	  ColdIncreaseCredits--;
	}
	/* this is a fake accessed page */
	else {
	  hand_cold->ref_bit = 0;
	  if (is_matched == 2) /* matched but also have demotion */
	    ColdIncreaseCredits--;
	  goto evictable_page;
	}
      }

      /* normal case: one promtion + one demotion */
      else {
	is_matched = run_HAND_hot(hand_cold);

	if (!is_matched || (is_matched==2)) {
	  temp_pg = hand_cold;
	  hand_cold = hand_cold->prev;
	  remove_from_clock(temp_pg, 0);	  
	  insert_to_clock_head(temp_pg);
	  temp_pg->is_cold_page = FALSE;
	  num_hot_pages++;
	}
      }

      /* search for next cold page for consideration */
      while (!hand_cold->is_cold_page) 
	hand_cold = hand_cold->prev;

      goto evictable_page;
    }

    return;
}


/* get the range of accessed blocks [1:N] and the number of references */ 
int get_range(FILE *trc_fp, long *p_vm_size, long *p_trc_len)
{
  long ref_blk;
  long count = 0;
  long min, max;

  fseek(trc_fp, 0, SEEK_SET);

  fscanf(trc_fp, "%ld", &ref_blk);
  max = min = ref_blk;

  while (!feof(trc_fp)){
    if (ref_blk < 0)
      return FALSE;
    count++;
    if (ref_blk > max)
      max = ref_blk;
    if (ref_blk < min)
      min = ref_blk;
    fscanf(trc_fp, "%ld", &ref_blk);
  }
  
  printf(" [%lu  %lu] for %lu refs in the trace\n", min, max, count);
  fseek(trc_fp, 0, SEEK_SET);
  *p_vm_size = max;
  *p_trc_len = count;
  return TRUE;
}

void stat_check()
{
  long l_num_hot_pages = 0;
  long l_num_non_res_pages = 0;
  long l_clock_size = 0;
  long l_residents = 0;

  page_struct *p = clock_head;

  if (!p)
    return;

  do {
    if (!p->is_cold_page)
      l_num_hot_pages++;

    if (p->is_resident)
      l_residents++;

    if (p->is_in_clock)
      l_clock_size++;

    if (!p->is_resident){
      if (!p->is_cold_page)
	printf("...%ld... ", p->page_num);
      assert(p->is_cold_page);
      assert(p->is_in_test);
      l_num_non_res_pages++;
    } 
    p = p->next;
  } while (p != clock_head);

  //if (l_residents + free_mem_size != mem_size)
  //printf("%d %d %d\n", l_residents, free_mem_size, mem_size);
  assert(l_residents + free_mem_size == mem_size);

  assert(l_num_hot_pages == num_hot_pages);
  assert(free_mem_size + num_cold_pages >= LOWEST_COLD_ALLOC);
  assert(num_cold_pages - free_mem_size <= mem_size - LOWEST_COLD_ALLOC);
  assert(l_clock_size == clock_size);
  assert(l_num_non_res_pages == num_non_res_pages);
  assert(l_num_non_res_pages <= MAX_NONE_RES_PAGE);
  assert(l_num_non_res_pages + l_residents == clock_size);
    
    return;
}


void view_clock(long ref_page)
{
  //#ifdef DEBUG
  //printf("\n No. %ld: to access %ld ...\n", total_pg_refs, ref_page); 

#ifdef DEBUG

  printf("\n No. %ld: to access %ld (%s)...\n", total_pg_refs, ref_page, page_tbl[ref_page].is_resident? "hit":"miss"); 
  printf("free_mem_size = %ld,  num_hot_pages = %ld  clock_size = %ld ColdIncreaseCredits = %d\n", 
	 free_mem_size, num_hot_pages, clock_size, ColdIncreaseCredits);

  p = clock_head;
  if (!p)
    return;

  do {
    if (p->is_cold_page){
      if(p->is_in_test)
	printf("{");
      else
	printf("(");
    }
    else
      printf("[");
    printf("%ld", p->page_num);
    if (p == hand_hot)
      printf("^");
    if (p == hand_test)
      printf("@");
    if (p == hand_cold)
      printf("*");
    if (p->ref_bit)
      printf(".");
    if (p->is_resident)
      printf("=");
    printf("  ");
    p = p->next;
    assert(p->prev->next == p);
  } while (p != clock_head);
  printf("\n\n");
#endif

  stat_check();
  return;
}

void init_one_page(page_struct *p)
{
  p->is_resident = FALSE; 
  p->is_cold_page = TRUE;
  
  p->next = NULL;
  p->prev = NULL;
  
  p->is_in_clock =  FALSE;
  p->is_in_test = FALSE;
  p->ref_bit = 0;

  return;
}

FILE *openReadFile(char file_name[])
{
  FILE *fp;

  fp = fopen(file_name, "r");

  if (!fp) {
    printf("can not find file %s.\n", file_name);
    return NULL;
  }
  
  return fp;
}


void remove_from_clock(page_struct * remove_pg, int is_permanent)
{
  assert(remove_pg != hand_cold);
  assert(remove_pg != hand_hot);
  assert(remove_pg != hand_test);
  assert(remove_pg != clock_head);

  remove_pg->prev->next = remove_pg->next;
  remove_pg->next->prev = remove_pg->prev;
  
  remove_pg->next = NULL;
  remove_pg->prev = NULL;
  remove_pg->is_in_clock =  FALSE;
  clock_size--;
  remove_pg->is_in_test = FALSE;

  remove_pg->ref_bit = 0;

  if (is_permanent) {
    remove_pg->is_resident = FALSE; 
    remove_pg->is_cold_page = TRUE;
  }

  return;
}


void insert_to_clock_head(page_struct * insert_pg)
{  
  assert(free_mem_size > 0 || clock_head != hand_hot);
  assert(free_mem_size > 0 || clock_head != hand_test);

  insert_pg->next = clock_head;
  insert_pg->prev = clock_head->prev;
  clock_head->prev->next = insert_pg;
  clock_head->prev = insert_pg;
  clock_head = insert_pg;

  clock_head->is_in_clock = TRUE;
  clock_size++;

  return;
}


void check_adaptive_bound()
{  
  if (ColdIncreaseCredits > 0 && num_cold_pages >= MAX_cold_alloc)
    ColdIncreaseCredits = 0;
  if (ColdIncreaseCredits < 0 && num_cold_pages <= MIN_cold_alloc)
    ColdIncreaseCredits = 0;	

  return;
}
